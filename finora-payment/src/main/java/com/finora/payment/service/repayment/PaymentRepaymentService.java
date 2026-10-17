package com.finora.payment.service.repayment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.domain.ledger.LedgerBalanceBucket;
import com.finora.payment.domain.ledger.LedgerDirection;
import com.finora.payment.domain.ledger.LedgerTransactionType;
import com.finora.payment.domain.outbox.PaymentOutboxEvent;
import com.finora.payment.domain.repayment.PaymentRepayment;
import com.finora.payment.domain.repayment.PaymentRepaymentStatus;
import com.finora.payment.domain.repayment.PaymentRepaymentType;
import com.finora.payment.domain.repayment.EarlySettlementQuote;
import com.finora.payment.domain.repayment.PartialPrepaymentQuote;
import com.finora.payment.domain.servicing.PaymentLoanAccount;
import com.finora.payment.domain.servicing.PaymentNoteOwnership;
import com.finora.payment.domain.wallet.WalletOwnerType;
import com.finora.payment.dto.response.RepaymentResponse;
import com.finora.payment.dto.response.ScheduledDueResponse;
import com.finora.payment.integration.fineract.FineractQuoteErrors;
import com.finora.payment.integration.fineract.FineractRepaymentException;
import com.finora.payment.integration.fineract.RepaymentCoreCommand;
import com.finora.payment.integration.fineract.RepaymentCoreGateway;
import com.finora.payment.integration.fineract.ScheduledRepaymentCoreQuote;
import com.finora.payment.messaging.RepaymentCancelledEventData;
import com.finora.payment.messaging.RepaymentCollectedEventData;
import com.finora.payment.messaging.RepaymentDistributedEventData;
import com.finora.payment.repository.PaymentOutboxEventRepository;
import com.finora.payment.repository.repayment.PaymentRepaymentRepository;
import com.finora.payment.repository.repayment.EarlySettlementQuoteRepository;
import com.finora.payment.repository.repayment.PartialPrepaymentQuoteRepository;
import com.finora.payment.repository.servicing.PaymentLoanAccountRepository;
import com.finora.payment.repository.servicing.PaymentNoteOwnershipRepository;
import com.finora.payment.service.ledger.LedgerPostingCommand;
import com.finora.payment.service.ledger.LedgerPostingEntryCommand;
import com.finora.payment.service.ledger.LedgerPostingResult;
import com.finora.payment.service.ledger.LedgerPostingService;
import com.finora.payment.service.wallet.WalletAccountService;
import com.finora.payment.service.wallet.WalletView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class PaymentRepaymentService {
    private final PaymentRepaymentRepository repayments;
    private final EarlySettlementQuoteRepository earlySettlementQuotes;
    private final PartialPrepaymentQuoteRepository partialPrepaymentQuotes;
    private final PaymentLoanAccountRepository loanAccounts;
    private final PaymentNoteOwnershipRepository ownershipRepository;
    private final PaymentOutboxEventRepository outbox;
    private final LedgerPostingService ledger;
    private final WalletAccountService wallets;
    private final RepaymentCoreGateway fineract;
    private final RepaymentAllocator allocator;
    private final RepaymentInFlightGuard inFlight;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public RepaymentResponse create(Long loanApplicationId, BigDecimal amount, LocalDate transactionDate,
            String idempotencyKey) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        BigDecimal normalized = amount.setScale(2);
        LocalDate effectiveDate = transactionDate == null ? LocalDate.now(clock) : transactionDate;
        if (!effectiveDate.equals(LocalDate.now(clock))) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "REPAYMENT_DATE_INVALID",
                    "Ngày trả nợ phải là ngày nghiệp vụ hiện tại");
        }
        CollectionPreparation preparation = transactionTemplate.execute(status -> prepareCollection(
                loanApplicationId, borrowerId, normalized, effectiveDate, idempotencyKey));
        if (preparation == null) throw new IllegalStateException("Không chuẩn bị được lệnh trả nợ");
        if (preparation.replay() != null) return preparation.replay();
        ScheduledRepaymentCoreQuote quote = FineractQuoteErrors.read(
                () -> fineract.scheduledRepaymentQuote(preparation.fineractLoanId()));
        if (!quote.accepts(normalized)) {
            BigDecimal partial = quote.partialOverdueAmount();
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "REPAYMENT_AMOUNT_NOT_SCHEDULED_DUE",
                    "Số tiền phải bằng nghĩa vụ hiện tại từ Fineract: " + quote.payableAmount().toPlainString()
                            + (partial == null ? "" : " (hoặc chỉ kỳ quá hạn cũ nhất: " + partial.toPlainString() + ")"));
        }
        PaymentRepaymentType type = quote.overdueAmount().signum() > 0
                ? PaymentRepaymentType.OVERDUE_CURE : PaymentRepaymentType.SCHEDULED;
        return transactionTemplate.execute(status -> collect(preparation, normalized, effectiveDate,
                type, quote.nextDueDate(), idempotencyKey));
    }

    /**
     * D16: trả kỳ trước ngày đến hạn trên khoản vay core V4 thì giữ ở clearing và ghi Fineract đúng ngày đến hạn, để lãi
     * của kỳ không đổi và các kỳ sau giữ nguyên. Core V1–V3 tính trước lãi nên ghi ngay vẫn đúng lịch; khoản khắc phục
     * quá hạn và khoản trả đúng ngày đến hạn luôn ghi ngay.
     */
    static LocalDate deferredPostingDate(PaymentLoanAccount account, PaymentRepaymentType type, LocalDate today,
            LocalDate dueDate) {
        if (type != PaymentRepaymentType.SCHEDULED || !account.dailyInterestCore() || dueDate == null) return null;
        return dueDate.isAfter(today) ? dueDate : null;
    }

    /**
     * Người vay hủy khoản trả kỳ trước hạn đang giữ (D16) trước ngày ghi nhận, ví dụ để trả trước một phần hoặc tất toán
     * theo dư nợ hiện tại. Tiền về lại số dư khả dụng; từ ngày ghi nhận trở đi khoản này không hủy được nữa.
     */
    @Transactional
    public RepaymentResponse cancelPending(UUID repaymentId) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        PaymentRepayment found = repayments.findByRepaymentId(repaymentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REPAYMENT_NOT_FOUND",
                        "Không tìm thấy giao dịch trả nợ"));
        if (!found.getBorrowerId().equals(borrowerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REPAYMENT_OWNER_MISMATCH",
                    "Không được hủy giao dịch của người khác");
        }
        // Cùng thứ tự khóa với lệnh trả kỳ: tài khoản vay trước, giao dịch sau.
        loanAccounts.findByLoanApplicationIdForUpdate(found.getLoanApplicationId());
        PaymentRepayment value = repayments.findByIdForUpdate(found.getId()).orElseThrow();
        if (value.getStatus() == PaymentRepaymentStatus.CANCELLED) return RepaymentResponse.from(value);
        LocalDate today = LocalDate.now(clock);
        if (!value.awaitingDueDate(today)) {
            throw new BusinessException(HttpStatus.CONFLICT, "REPAYMENT_NOT_CANCELLABLE",
                    "Chỉ hủy được khoản trả kỳ trước hạn khi chưa tới ngày ghi nhận vào sổ nợ");
        }
        WalletView borrowerWallet = wallets.open(WalletOwnerType.BORROWER, value.getBorrowerId(), value.getCurrency());
        ledger.post(new LedgerPostingCommand("REPAYMENT-CANCEL-REFUND:" + value.getRepaymentId(),
                LedgerTransactionType.ADJUSTMENT, "REPAYMENT_CANCEL_REFUND", value.getRepaymentId().toString(),
                value.getCurrency(), List.of(
                    new LedgerPostingEntryCommand(null, "CLEARING:REPAYMENT", LedgerBalanceBucket.CLEARING,
                            LedgerDirection.DEBIT, value.getAmount()),
                    new LedgerPostingEntryCommand(borrowerWallet.walletId(), null, LedgerBalanceBucket.AVAILABLE,
                            LedgerDirection.CREDIT, value.getAmount()))));
        Instant now = clock.instant();
        value.cancelBeforeDueDate(today, now);
        // P5 (D77): cùng transaction với hoàn tiền; lần hủy lặp lại đã trả sớm ở trên nên không phát event thứ hai.
        outbox.save(PaymentOutboxEvent.pending(value.getLoanApplicationId().toString(), "RepaymentCancelled",
                json(new RepaymentCancelledEventData(value.getRepaymentId(), value.getLoanApplicationId(),
                        value.getBorrowerId(), value.getPostOn(), now)), now));
        return RepaymentResponse.from(value);
    }

    @Transactional
    public RepaymentResponse createEarlySettlement(UUID quoteId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key không được để trống");
        }
        String borrowerId = SecurityUtils.getCurrentUserId();
        PaymentRepayment replay = repayments.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (replay != null) {
            requireSameEarlySettlement(replay, quoteId, borrowerId);
            return RepaymentResponse.from(replay);
        }
        EarlySettlementQuote quote = earlySettlementQuotes.findByQuoteIdForUpdate(quoteId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "EARLY_SETTLEMENT_QUOTE_NOT_FOUND",
                        "Không tìm thấy báo giá tất toán"));
        try {
            quote.consume(borrowerId, clock.instant());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "EARLY_SETTLEMENT_QUOTE_OWNER_MISMATCH",
                    exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "EARLY_SETTLEMENT_QUOTE_NOT_ACTIVE",
                    exception.getMessage());
        }
        PaymentLoanAccount account = loanAccounts.findByLoanApplicationIdForUpdate(quote.getLoanApplicationId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_LOAN_NOT_FOUND",
                        "Khoản vay chưa sẵn sàng để tất toán"));
        if (account.getStatus() != PaymentLoanAccount.Status.ACTIVE
                || !account.getBorrowerId().equals(borrowerId)
                || !account.getFineractLoanId().equals(quote.getFineractLoanId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "EARLY_SETTLEMENT_LOAN_CHANGED",
                    "Khoản vay không còn khớp với báo giá tất toán");
        }
        // Một khoản trả kỳ được thu sau khi lập báo giá làm số tất toán cũ thu thừa.
        inFlight.requireUnchangedSince(account.getLoanApplicationId(), quote.getCreatedAt(),
                "EARLY_SETTLEMENT_QUOTE_STALE");
        WalletView wallet = wallets.open(WalletOwnerType.BORROWER, borrowerId, account.getCurrency());
        LedgerPostingResult posting = ledger.post(new LedgerPostingCommand(
                "EARLY-SETTLEMENT-COLLECT:" + idempotencyKey, LedgerTransactionType.REPAYMENT,
                "EARLY_SETTLEMENT", account.getLoanApplicationId().toString(), account.getCurrency(), List.of(
                    new LedgerPostingEntryCommand(wallet.walletId(), null, LedgerBalanceBucket.AVAILABLE,
                            LedgerDirection.DEBIT, quote.getTotalAmount()),
                    new LedgerPostingEntryCommand(null, "CLEARING:REPAYMENT", LedgerBalanceBucket.CLEARING,
                            LedgerDirection.CREDIT, quote.getTotalAmount()))));
        PaymentRepayment repayment = repayments.save(PaymentRepayment.earlySettlement(idempotencyKey,
                account, quote, posting.transactionId(), clock.instant()));
        return RepaymentResponse.from(repayment);
    }

    @Transactional
    public RepaymentResponse createPartialPrepayment(UUID quoteId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key không được để trống");
        }
        String borrowerId = SecurityUtils.getCurrentUserId();
        PaymentRepayment replay = repayments.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (replay != null) {
            requireSamePartialPrepayment(replay, quoteId, borrowerId);
            return RepaymentResponse.from(replay);
        }
        PartialPrepaymentQuote quote = partialPrepaymentQuotes.findByQuoteIdForUpdate(quoteId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "PARTIAL_PREPAYMENT_QUOTE_NOT_FOUND", "Không tìm thấy báo giá trả trước một phần"));
        try {
            quote.consume(borrowerId, clock.instant());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PARTIAL_PREPAYMENT_QUOTE_OWNER_MISMATCH",
                    exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new BusinessException(HttpStatus.CONFLICT, "PARTIAL_PREPAYMENT_QUOTE_NOT_ACTIVE",
                    exception.getMessage());
        }
        PaymentLoanAccount account = loanAccounts.findByLoanApplicationIdForUpdate(quote.getLoanApplicationId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_LOAN_NOT_FOUND",
                        "Khoản vay chưa sẵn sàng để trả trước"));
        if (account.getStatus() != PaymentLoanAccount.Status.ACTIVE
                || !account.getBorrowerId().equals(borrowerId)
                || !account.getFineractLoanId().equals(quote.getFineractLoanId())
                || !account.getCoreConfigVersion().equals(quote.getCoreConfigVersion())) {
            throw new BusinessException(HttpStatus.CONFLICT, "PARTIAL_PREPAYMENT_LOAN_CHANGED",
                    "Khoản vay không còn khớp với báo giá trả trước một phần");
        }
        inFlight.requireUnchangedSince(account.getLoanApplicationId(), quote.getCreatedAt(),
                "PARTIAL_PREPAYMENT_QUOTE_STALE");
        WalletView wallet = wallets.open(WalletOwnerType.BORROWER, borrowerId, account.getCurrency());
        LedgerPostingResult posting = ledger.post(new LedgerPostingCommand(
                "PARTIAL-PREPAYMENT-COLLECT:" + idempotencyKey, LedgerTransactionType.REPAYMENT,
                "PARTIAL_PREPAYMENT", account.getLoanApplicationId().toString(), account.getCurrency(), List.of(
                    new LedgerPostingEntryCommand(wallet.walletId(), null, LedgerBalanceBucket.AVAILABLE,
                            LedgerDirection.DEBIT, quote.getTotalAmount()),
                    new LedgerPostingEntryCommand(null, "CLEARING:REPAYMENT", LedgerBalanceBucket.CLEARING,
                            LedgerDirection.CREDIT, quote.getTotalAmount()))));
        return RepaymentResponse.from(repayments.save(PaymentRepayment.partialPrepayment(idempotencyKey,
                account, quote, posting.transactionId(), clock.instant())));
    }

    /**
     * Đọc nghĩa vụ hiện tại từ Fineract cho đúng người vay sở hữu khoản vay, không trừ tiền và không ghi gì. Mobile dùng
     * số này thay cho projection để lệnh trả nợ không bị từ chối vì lệch nghĩa vụ.
     */
    public ScheduledDueResponse scheduledDue(Long loanApplicationId) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        PaymentLoanAccount account = transactionTemplate.execute(status -> requireOwnedActiveAccount(loanApplicationId, borrowerId));
        if (account == null) throw new IllegalStateException("Không đọc được khoản vay");
        ScheduledRepaymentCoreQuote quote = FineractQuoteErrors.read(
                () -> fineract.scheduledRepaymentQuote(account.getFineractLoanId()));
        BigDecimal overdue = quote.overdueAmount().setScale(2, java.math.RoundingMode.UNNECESSARY);
        LocalDate today = LocalDate.now(clock);
        PaymentRepaymentType type = overdue.signum() > 0
                ? PaymentRepaymentType.OVERDUE_CURE : PaymentRepaymentType.SCHEDULED;
        // Khoản đã thu mà core chưa ghi: Fineract vẫn báo kỳ đó chưa trả, client phải hiển thị "đã thanh toán, chờ ghi nhận".
        ScheduledDueResponse.PendingRepayment pending = transactionTemplate.execute(status ->
                inFlight.oldestInFlight(account.getLoanApplicationId())
                        .map(value -> ScheduledDueResponse.PendingRepayment.from(value, today)).orElse(null));
        BigDecimal partial = quote.partialOverdueAmount();
        return new ScheduledDueResponse(account.getLoanApplicationId(),
                quote.payableAmount().setScale(2, java.math.RoundingMode.UNNECESSARY), overdue,
                partial == null ? null : partial.setScale(2, java.math.RoundingMode.UNNECESSARY), account.getCurrency(),
                type, today, quote.nextDueDate(), deferredPostingDate(account, type, today, quote.nextDueDate()),
                pending);
    }

    private PaymentLoanAccount requireOwnedActiveAccount(Long loanApplicationId, String borrowerId) {
        PaymentLoanAccount account = loanAccounts.findByLoanApplicationId(loanApplicationId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_LOAN_NOT_FOUND",
                        "Khoản vay chưa sẵn sàng để thu nợ"));
        if (!account.getBorrowerId().equals(borrowerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PAYMENT_LOAN_OWNER_MISMATCH",
                    "Khoản vay không thuộc người dùng hiện tại");
        }
        if (account.getStatus() != PaymentLoanAccount.Status.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_LOAN_NOT_ACTIVE", "Khoản vay không còn hoạt động");
        }
        return account;
    }

    private CollectionPreparation prepareCollection(Long loanApplicationId, String borrowerId, BigDecimal amount,
            LocalDate effectiveDate, String idempotencyKey) {
        PaymentLoanAccount account = loanAccounts.findByLoanApplicationId(loanApplicationId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_LOAN_NOT_FOUND",
                        "Khoản vay chưa sẵn sàng để thu nợ"));
        if (!account.getBorrowerId().equals(borrowerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PAYMENT_LOAN_OWNER_MISMATCH",
                    "Khoản vay không thuộc người dùng hiện tại");
        }
        if (account.getStatus() != PaymentLoanAccount.Status.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_LOAN_NOT_ACTIVE", "Khoản vay không còn hoạt động");
        }
        PaymentRepayment replay = repayments.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (replay != null) {
            requireSame(replay, account, amount, effectiveDate);
            return new CollectionPreparation(account.getLoanApplicationId(), account.getFineractLoanId(),
                    account.getCurrency(), borrowerId, RepaymentResponse.from(replay));
        }
        return new CollectionPreparation(account.getLoanApplicationId(), account.getFineractLoanId(),
                account.getCurrency(), borrowerId, null);
    }

    private RepaymentResponse collect(CollectionPreparation preparation, BigDecimal amount,
            LocalDate effectiveDate, PaymentRepaymentType repaymentType, LocalDate dueDate, String idempotencyKey) {
        // Khóa tài khoản vay: hai lệnh trả đồng thời không được cùng thấy "chưa có khoản chờ" rồi thu hai lần một kỳ.
        PaymentLoanAccount account = loanAccounts.findByLoanApplicationIdForUpdate(preparation.loanApplicationId())
                .orElseThrow(() -> new IllegalStateException("Khoản vay biến mất khi thu tiền"));
        PaymentRepayment replay = repayments.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (replay != null) {
            requireSame(replay, account, amount, effectiveDate);
            return RepaymentResponse.from(replay);
        }
        inFlight.requireNoneBeforeScheduledPayment(account.getLoanApplicationId());
        LocalDate postOn = deferredPostingDate(account, repaymentType, effectiveDate, dueDate);
        WalletView borrowerWallet = wallets.open(WalletOwnerType.BORROWER, preparation.borrowerId(), account.getCurrency());
        LedgerPostingResult posting = ledger.post(new LedgerPostingCommand("REPAYMENT-COLLECT:" + idempotencyKey,
                LedgerTransactionType.REPAYMENT, "LOAN_REPAYMENT", account.getLoanApplicationId().toString(),
                account.getCurrency(), List.of(
                    new LedgerPostingEntryCommand(borrowerWallet.walletId(), null, LedgerBalanceBucket.AVAILABLE,
                            LedgerDirection.DEBIT, amount),
                    new LedgerPostingEntryCommand(null, "CLEARING:REPAYMENT", LedgerBalanceBucket.CLEARING,
                            LedgerDirection.CREDIT, amount))));
        Instant now = clock.instant();
        PaymentRepayment repayment = repayments.save(PaymentRepayment.collected(idempotencyKey, account,
                amount, effectiveDate, repaymentType, postOn, posting.transactionId(), now));
        if (postOn != null) {
            // P5 (D77): khoản trả kỳ được giữ tới ngày đến hạn (D16) — Fineract chưa biết nên Notification cần event này để
            // không nhắc kỳ đó. Cùng transaction với bút toán trừ ví; lệnh gửi lại cùng khóa đã trả sớm ở trên.
            outbox.save(PaymentOutboxEvent.pending(account.getLoanApplicationId().toString(), "RepaymentCollected",
                    json(new RepaymentCollectedEventData(repayment.getRepaymentId(), repayment.getLoanApplicationId(),
                            repayment.getBorrowerId(), repayment.getRepaymentType().name(),
                            repayment.getAmount().toPlainString(), repayment.getCurrency(), postOn, now)), now));
        }
        return RepaymentResponse.from(repayment);
    }

    @Transactional(readOnly = true)
    public RepaymentResponse get(UUID repaymentId) {
        PaymentRepayment value = repayments.findByRepaymentId(repaymentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REPAYMENT_NOT_FOUND", "Không tìm thấy giao dịch trả nợ"));
        if (!value.getBorrowerId().equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REPAYMENT_OWNER_MISMATCH", "Không được xem giao dịch của người khác");
        }
        return RepaymentResponse.from(value);
    }

    @Transactional(readOnly = true)
    public List<Long> collectedIds() {
        // Khoản trả kỳ trước hạn (D16) chỉ tới lượt khi đã tới ngày đến hạn theo ngày nghiệp vụ Việt Nam.
        return repayments.findIdsByStatusDueBy(PaymentRepaymentStatus.COLLECTED, LocalDate.now(clock),
                PageRequest.of(0, 20));
    }

    @Transactional(readOnly = true)
    public List<Long> corePostedIds() {
        return repayments.findIdsByStatus(PaymentRepaymentStatus.CORE_POSTED, PageRequest.of(0, 20));
    }

    @Transactional(readOnly = true)
    public List<Long> reconciliationRequiredIds() {
        return repayments.findIdsByStatus(PaymentRepaymentStatus.RECONCILIATION_REQUIRED, PageRequest.of(0, 20));
    }

    @Transactional(readOnly = true)
    public List<Long> staleCorePostingIds(Instant cutoff) {
        return repayments.findIdsByStatusBefore(PaymentRepaymentStatus.CORE_POSTING, cutoff, PageRequest.of(0, 20));
    }

    @Transactional
    public void recoverStaleCorePosting(Long id) {
        repayments.findByIdForUpdate(id).ifPresent(value -> value.recoverStaleCorePosting(clock.instant()));
    }

    public void postToCore(Long id) {
        CoreWork work = transactionTemplate.execute(status -> claim(id));
        if (work == null) return;
        try {
            PaymentRepayment.CoreBreakdown result = fineract.postRepayment(new RepaymentCoreCommand(
                    work.fineractLoanId(), work.reference(), work.transactionDate(), work.amount()));
            transactionTemplate.executeWithoutResult(status -> saveCoreResult(work.id(), result));
        } catch (FineractRepaymentException exception) {
            transactionTemplate.executeWithoutResult(status -> recordCoreFailure(work.id(), exception));
        }
    }

    public void reconcileCorePosting(Long id) {
        CoreWork work = transactionTemplate.execute(status -> reconciliationWork(id));
        if (work == null) return;
        try {
            fineract.findPostedRepayment(work.fineractLoanId(), work.reference())
                    .ifPresent(result -> transactionTemplate.executeWithoutResult(
                            status -> saveReconciledCoreResult(work.id(), result)));
        } catch (FineractRepaymentException ignored) {
            // Đối soát là read-only. Giữ nguyên RECONCILIATION_REQUIRED để lần sau đọc lại;
            // không POST repayment mới và không hoàn tiền khi kết quả core còn chưa chắc chắn.
        }
    }

    /**
     * B32 (D28): admin ghi lại khoản trả nợ RECONCILIATION_REQUIRED mà Fineract chưa có. Thứ tự: tra cứu theo externalId
     * (có thì chỉ ghi nhận kết quả) → nhận việc → POST lại với đúng externalId, số tiền và ngày ghi nhận ban đầu. Fineract
     * từ chối (4xx) thì tra cứu thêm một lần trước khi hoàn tiền, vì lỗi có thể là trùng externalId với giao dịch đã ghi;
     * không tra được thì giữ đối soát, không hoàn tiền khi kết quả core chưa chắc chắn.
     *
     * @throws FineractRepaymentException khi chưa đọc được Fineract ở bước tra cứu đầu; khoản trả nợ không đổi
     */
    public void repostCorePosting(Long id, String actorId, java.time.Duration safeAfter) {
        CoreWork work = transactionTemplate.execute(status -> repostWork(id, safeAfter));
        if (work == null) throw new IllegalStateException("Không đọc được khoản trả nợ cần ghi lại");
        java.util.Optional<PaymentRepayment.CoreBreakdown> posted =
                fineract.findPostedRepayment(work.fineractLoanId(), work.reference());
        if (posted.isPresent()) {
            transactionTemplate.executeWithoutResult(status -> saveReconciledCoreResult(work.id(), posted.get()));
            return;
        }
        Boolean claimed = transactionTemplate.execute(status -> claimRepost(work.id(), actorId));
        // Worker đối soát vừa ghi nhận kết quả trong lúc tra cứu: không còn gì để ghi lại.
        if (!Boolean.TRUE.equals(claimed)) return;
        try {
            PaymentRepayment.CoreBreakdown result = fineract.postRepayment(new RepaymentCoreCommand(
                    work.fineractLoanId(), work.reference(), work.transactionDate(), work.amount()));
            transactionTemplate.executeWithoutResult(status -> saveCoreResult(work.id(), result));
        } catch (FineractRepaymentException exception) {
            if (exception.outcomeUnknown()) {
                transactionTemplate.executeWithoutResult(status -> recordCoreFailure(work.id(), exception));
                return;
            }
            java.util.Optional<PaymentRepayment.CoreBreakdown> duplicate;
            try {
                duplicate = fineract.findPostedRepayment(work.fineractLoanId(), work.reference());
            } catch (FineractRepaymentException lookupFailure) {
                transactionTemplate.executeWithoutResult(status -> repayments.findByIdForUpdate(work.id())
                        .orElseThrow().requireReconciliation("FINERACT_REPOST_REJECTED_UNVERIFIED",
                                "Fineract từ chối lần ghi lại nhưng chưa tra cứu được theo externalId; không hoàn tiền",
                                clock.instant()));
                return;
            }
            if (duplicate.isPresent()) {
                transactionTemplate.executeWithoutResult(status -> saveCoreResult(work.id(), duplicate.get()));
            } else {
                transactionTemplate.executeWithoutResult(status -> recordCoreFailure(work.id(), exception));
            }
        }
    }

    @Transactional
    public void distribute(Long id) {
        PaymentRepayment repayment = repayments.findByIdForUpdate(id).orElseThrow();
        if (repayment.getStatus() == PaymentRepaymentStatus.COMPLETED) return;
        if (repayment.getStatus() != PaymentRepaymentStatus.CORE_POSTED) return;
        List<PaymentNoteOwnership> notes = ownershipRepository.findActiveByLoanIdForUpdate(repayment.getLoanApplicationId());
        if (notes.isEmpty()) throw new IllegalStateException("Chưa nhận được quyền sở hữu Note để phân phối repayment");
        BigDecimal noteOutstanding = notes.stream().map(PaymentNoteOwnership::getOutstandingPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (repayment.getPrincipalAmount().compareTo(noteOutstanding) > 0) {
            throw new IllegalStateException("Gốc Fineract vượt tổng dư nợ Note");
        }
        Map<Long, BigDecimal> principal = allocator.allocate(repayment.getPrincipalAmount(), notes);
        Map<Long, BigDecimal> interest = allocator.allocate(repayment.getInterestAmount(), notes);
        Map<Long, BigDecimal> investorFee = allocator.allocate(repayment.getInvestorFeeShare(), notes);
        Map<String, BigDecimal> byInvestor = new LinkedHashMap<>();
        List<RepaymentDistributedEventData.NoteAllocation> allocations = new ArrayList<>();
        for (PaymentNoteOwnership note : notes) {
            BigDecimal notePrincipal = principal.get(note.getNoteId());
            BigDecimal noteInterest = interest.get(note.getNoteId());
            BigDecimal notePrepaymentFee = investorFee.get(note.getNoteId());
            note.repayPrincipal(notePrincipal, clock.instant());
            byInvestor.merge(note.getInvestorId(), notePrincipal.add(noteInterest).add(notePrepaymentFee),
                    BigDecimal::add);
            allocations.add(new RepaymentDistributedEventData.NoteAllocation(note.getNoteId(), note.getNoteNumber(),
                    note.getInvestorId(), notePrincipal.toPlainString(), noteInterest.toPlainString(),
                    notePrepaymentFee.toPlainString()));
        }
        List<LedgerPostingEntryCommand> entries = new ArrayList<>();
        entries.add(new LedgerPostingEntryCommand(null, "CLEARING:REPAYMENT", LedgerBalanceBucket.CLEARING,
                LedgerDirection.DEBIT, repayment.getAmount()));
        byInvestor.forEach((investorId, value) -> {
            if (value.signum() > 0) {
                WalletView investorWallet = wallets.open(WalletOwnerType.INVESTOR, investorId, repayment.getCurrency());
                entries.add(new LedgerPostingEntryCommand(investorWallet.walletId(), null, LedgerBalanceBucket.AVAILABLE,
                        LedgerDirection.CREDIT, value));
            }
        });
        // Phí core, phạt và 20% phí trả trước thuộc FINORA; 80% phí trả trước đã được cộng vào ví NĐT.
        BigDecimal coreFee = repayment.getFeeAmount().subtract(repayment.getPlatformFee());
        BigDecimal charges = coreFee.add(repayment.getPenaltyAmount()).add(repayment.getFinoraFeeShare());
        if (charges.signum() > 0) {
            WalletView platform = wallets.open(WalletOwnerType.PLATFORM, "FINORA", repayment.getCurrency());
            entries.add(new LedgerPostingEntryCommand(platform.walletId(), null, LedgerBalanceBucket.AVAILABLE,
                    LedgerDirection.CREDIT, charges));
        }
        LedgerPostingResult distribution = ledger.post(new LedgerPostingCommand(
                "REPAYMENT-DISTRIBUTE:" + repayment.getRepaymentId(), LedgerTransactionType.DISTRIBUTION,
                "LOAN_REPAYMENT", repayment.getRepaymentId().toString(), repayment.getCurrency(), entries));
        var now = clock.instant();
        repayment.complete(distribution.transactionId(), now);
        if (repayment.getTotalOutstanding().signum() == 0) {
            loanAccounts.findByLoanApplicationIdForUpdate(repayment.getLoanApplicationId())
                    .ifPresent(account -> account.close(now));
        }
        RepaymentDistributedEventData event = new RepaymentDistributedEventData(repayment.getRepaymentId(),
                reference(repayment), repayment.getRepaymentType().name(),
                businessQuoteId(repayment),
                repayment.getPlatformFee().toPlainString(),
                repayment.getInvestorFeeShare().toPlainString(), repayment.getFinoraFeeShare().toPlainString(),
                repayment.getLoanApplicationId(), repayment.getFineractLoanId(),
                repayment.getFineractTransactionId(), repayment.getAmount().toPlainString(),
                repayment.getPrincipalAmount().toPlainString(), repayment.getInterestAmount().toPlainString(),
                repayment.getFeeAmount().toPlainString(), repayment.getPenaltyAmount().toPlainString(),
                repayment.getOutstandingPrincipal().toPlainString(), repayment.getOutstandingInterest().toPlainString(),
                repayment.getOutstandingFee().toPlainString(), repayment.getOutstandingPenalty().toPlainString(),
                repayment.getTotalOutstanding().toPlainString(), repayment.getOverdueAmount().toPlainString(),
                repayment.getCurrency(),
                repayment.getTransactionDate(), repayment.getNextDueDate(), repayment.getNextDueAmount().toPlainString(),
                now, allocations, repayment.getBorrowerId());
        outbox.save(PaymentOutboxEvent.pending(repayment.getLoanApplicationId().toString(),
                "RepaymentDistributed", json(event), now));
    }

    @Transactional
    CoreWork claim(Long id) {
        PaymentRepayment value = repayments.findByIdForUpdate(id).orElseThrow();
        if (value.getStatus() != PaymentRepaymentStatus.COLLECTED) return null;
        // Danh sách có thể được đọc trước nửa đêm: không bao giờ ghi core trước ngày đến hạn đã hẹn.
        if (value.awaitingDueDate(LocalDate.now(clock))) return null;
        value.startCorePosting(clock.instant());
        return new CoreWork(value.getId(), value.getFineractLoanId(), reference(value),
                value.corePostingDate(), value.getCoreAmount());
    }

    @Transactional
    void saveCoreResult(Long id, PaymentRepayment.CoreBreakdown result) {
        PaymentRepayment value = repayments.findByIdForUpdate(id).orElseThrow();
        if (value.getStatus() == PaymentRepaymentStatus.CORE_POSTED || value.getStatus() == PaymentRepaymentStatus.COMPLETED) return;
        if (value.getStatus() != PaymentRepaymentStatus.CORE_POSTING) throw new IllegalStateException("Repayment không ở bước ghi core");
        value.corePosted(result, clock.instant());
    }

    @Transactional
    void recordCoreFailure(Long id, FineractRepaymentException exception) {
        PaymentRepayment value = repayments.findByIdForUpdate(id).orElseThrow();
        if (exception.outcomeUnknown()) {
            value.requireReconciliation(exception.code(), exception.getMessage(), clock.instant());
            return;
        }
        WalletView borrowerWallet = wallets.open(WalletOwnerType.BORROWER, value.getBorrowerId(), value.getCurrency());
        ledger.post(new LedgerPostingCommand("REPAYMENT-REFUND:" + value.getRepaymentId(),
                LedgerTransactionType.ADJUSTMENT, "REPAYMENT_REFUND", value.getRepaymentId().toString(),
                value.getCurrency(), List.of(
                    new LedgerPostingEntryCommand(null, "CLEARING:REPAYMENT", LedgerBalanceBucket.CLEARING,
                            LedgerDirection.DEBIT, value.getAmount()),
                    new LedgerPostingEntryCommand(borrowerWallet.walletId(), null, LedgerBalanceBucket.AVAILABLE,
                            LedgerDirection.CREDIT, value.getAmount()))));
        value.fail(exception.code(), exception.getMessage(), clock.instant());
    }

    @Transactional
    CoreWork repostWork(Long id, java.time.Duration safeAfter) {
        PaymentRepayment value = repayments.findByIdForUpdate(id).orElseThrow();
        if (value.getStatus() != PaymentRepaymentStatus.RECONCILIATION_REQUIRED) {
            throw new BusinessException(HttpStatus.CONFLICT, "REPAYMENT_NOT_RECONCILABLE",
                    "Chỉ giao dịch RECONCILIATION_REQUIRED mới được ghi lại vào hệ thống lõi");
        }
        Instant availableAt = value.coreRepostAvailableAt(safeAfter);
        if (clock.instant().isBefore(availableAt)) {
            throw new BusinessException(HttpStatus.CONFLICT, "REPAYMENT_REPOST_TOO_EARLY",
                    "Lần ghi hệ thống lõi gần nhất chưa đủ thời gian an toàn; chỉ ghi lại được từ " + availableAt);
        }
        return new CoreWork(value.getId(), value.getFineractLoanId(), reference(value),
                value.corePostingDate(), value.getCoreAmount());
    }

    @Transactional
    boolean claimRepost(Long id, String actorId) {
        PaymentRepayment value = repayments.findByIdForUpdate(id).orElseThrow();
        if (value.getStatus() != PaymentRepaymentStatus.RECONCILIATION_REQUIRED) return false;
        value.startCoreRepost(actorId, clock.instant());
        return true;
    }

    @Transactional
    CoreWork reconciliationWork(Long id) {
        PaymentRepayment value = repayments.findByIdForUpdate(id).orElseThrow();
        if (value.getStatus() != PaymentRepaymentStatus.RECONCILIATION_REQUIRED) return null;
        return new CoreWork(value.getId(), value.getFineractLoanId(), reference(value),
                value.corePostingDate(), value.getCoreAmount());
    }

    @Transactional
    void saveReconciledCoreResult(Long id, PaymentRepayment.CoreBreakdown result) {
        PaymentRepayment value = repayments.findByIdForUpdate(id).orElseThrow();
        if (value.getStatus() != PaymentRepaymentStatus.RECONCILIATION_REQUIRED) return;
        value.corePosted(result, clock.instant());
    }

    private void requireSame(PaymentRepayment value, PaymentLoanAccount account, BigDecimal amount, LocalDate date) {
        if ((value.getRepaymentType() != PaymentRepaymentType.SCHEDULED
                && value.getRepaymentType() != PaymentRepaymentType.OVERDUE_CURE)
                || !value.getLoanApplicationId().equals(account.getLoanApplicationId())
                || value.getAmount().compareTo(amount) != 0 || !value.getTransactionDate().equals(date)) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key đã được dùng với lệnh trả nợ khác");
        }
    }

    private void requireSameEarlySettlement(PaymentRepayment value, UUID quoteId, String borrowerId) {
        if (value.getRepaymentType() != PaymentRepaymentType.EARLY_SETTLEMENT
                || !quoteId.equals(value.getQuoteId()) || !borrowerId.equals(value.getBorrowerId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key đã được dùng với lệnh trả nợ khác");
        }
    }

    private void requireSamePartialPrepayment(PaymentRepayment value, UUID quoteId, String borrowerId) {
        if (value.getRepaymentType() != PaymentRepaymentType.PARTIAL_PREPAYMENT
                || !quoteId.equals(value.getPartialPrepaymentQuoteId())
                || !borrowerId.equals(value.getBorrowerId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key đã được dùng với lệnh trả nợ khác");
        }
    }

    private String businessQuoteId(PaymentRepayment value) {
        UUID quote = value.getPartialPrepaymentQuoteId() != null
                ? value.getPartialPrepaymentQuoteId() : value.getQuoteId();
        return quote == null ? null : quote.toString();
    }

    private String reference(PaymentRepayment value) { return "FINORA-REPAY-" + value.getRepaymentId(); }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Không serialize được repayment event", exception); }
    }

    record CoreWork(Long id, Long fineractLoanId, String reference, LocalDate transactionDate, BigDecimal amount) {}
    record CollectionPreparation(Long loanApplicationId, Long fineractLoanId, String currency,
            String borrowerId, RepaymentResponse replay) {}
}
