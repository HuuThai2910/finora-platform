package com.finora.payment.domain.repayment;

import com.finora.payment.domain.servicing.PaymentLoanAccount;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_repayments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRepayment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "repayment_id", nullable = false, unique = true, updatable = false) private UUID repaymentId;
    @Column(name = "idempotency_key", nullable = false, length = 100, unique = true, updatable = false) private String idempotencyKey;
    @Column(name = "loan_application_id", nullable = false, updatable = false) private Long loanApplicationId;
    @Column(name = "borrower_id", nullable = false, length = 100, updatable = false) private String borrowerId;
    @Column(name = "fineract_loan_id", nullable = false, updatable = false) private Long fineractLoanId;
    @Column(nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal amount;
    @Enumerated(EnumType.STRING) @Column(name = "repayment_type", nullable = false, length = 30, updatable = false)
    private PaymentRepaymentType repaymentType;
    @Column(name = "quote_id", unique = true, updatable = false) private UUID quoteId;
    @Column(name = "partial_prepayment_quote_id", unique = true, updatable = false) private UUID partialPrepaymentQuoteId;
    @Column(name = "core_amount", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal coreAmount;
    @Column(name = "platform_fee", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal platformFee;
    @Column(name = "investor_fee_share", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal investorFeeShare;
    @Column(name = "finora_fee_share", nullable = false, precision = 18, scale = 2, updatable = false) private BigDecimal finoraFeeShare;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Column(name = "transaction_date", nullable = false, updatable = false) private LocalDate transactionDate;
    /**
     * Ngày ghi giao dịch vào Fineract khi khác ngày thu (D16): khoản trả kỳ trước hạn của khoản vay core V4 được giữ ở
     * clearing và ghi đúng ngày đến hạn để lãi của kỳ không đổi. {@code null}: ghi ngay theo {@link #transactionDate}.
     */
    @Column(name = "post_on", updatable = false) private LocalDate postOn;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private PaymentRepaymentStatus status;
    @Column(name = "wallet_ledger_transaction_id", nullable = false, updatable = false) private UUID walletLedgerTransactionId;
    @Column(name = "distribution_ledger_transaction_id") private UUID distributionLedgerTransactionId;
    @Column(name = "fineract_transaction_id", unique = true) private Long fineractTransactionId;
    @Column(name = "principal_amount", precision = 18, scale = 2) private BigDecimal principalAmount;
    @Column(name = "interest_amount", precision = 18, scale = 2) private BigDecimal interestAmount;
    @Column(name = "fee_amount", precision = 18, scale = 2) private BigDecimal feeAmount;
    @Column(name = "penalty_amount", precision = 18, scale = 2) private BigDecimal penaltyAmount;
    @Column(name = "outstanding_principal", precision = 18, scale = 2) private BigDecimal outstandingPrincipal;
    @Column(name = "outstanding_interest", precision = 18, scale = 2) private BigDecimal outstandingInterest;
    @Column(name = "outstanding_fee", precision = 18, scale = 2) private BigDecimal outstandingFee;
    @Column(name = "outstanding_penalty", precision = 18, scale = 2) private BigDecimal outstandingPenalty;
    @Column(name = "total_outstanding", precision = 18, scale = 2) private BigDecimal totalOutstanding;
    @Column(name = "overdue_amount", precision = 18, scale = 2) private BigDecimal overdueAmount;
    @Column(name = "next_due_date") private LocalDate nextDueDate;
    @Column(name = "next_due_amount", precision = 18, scale = 2) private BigDecimal nextDueAmount;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "error_code", length = 80) private String errorCode;
    @Column(name = "error_detail", length = 500) private String errorDetail;
    @Column(name = "completed_at") private Instant completedAt;
    /** Số lần admin ghi lại vào Fineract sau khi tra cứu xác nhận core chưa có giao dịch (B32, D28). */
    @Column(name = "core_repost_count", nullable = false) private int coreRepostCount;
    @Column(name = "last_core_repost_by", length = 100) private String lastCoreRepostBy;
    @Column(name = "last_core_repost_at") private Instant lastCoreRepostAt;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static PaymentRepayment collected(String idempotencyKey, PaymentLoanAccount account,
            BigDecimal amount, LocalDate transactionDate, UUID walletLedgerTransactionId, Instant now) {
        return collected(idempotencyKey, account, amount, transactionDate, PaymentRepaymentType.SCHEDULED,
                walletLedgerTransactionId, now);
    }

    public static PaymentRepayment collected(String idempotencyKey, PaymentLoanAccount account,
            BigDecimal amount, LocalDate transactionDate, PaymentRepaymentType repaymentType,
            UUID walletLedgerTransactionId, Instant now) {
        return collected(idempotencyKey, account, amount, transactionDate, repaymentType, null,
                walletLedgerTransactionId, now);
    }

    public static PaymentRepayment collected(String idempotencyKey, PaymentLoanAccount account,
            BigDecimal amount, LocalDate transactionDate, PaymentRepaymentType repaymentType, LocalDate postOn,
            UUID walletLedgerTransactionId, Instant now) {
        if (repaymentType != PaymentRepaymentType.SCHEDULED
                && repaymentType != PaymentRepaymentType.OVERDUE_CURE) {
            throw new IllegalArgumentException("Factory collected chỉ dùng cho trả kỳ hoặc khắc phục quá hạn");
        }
        if (postOn != null && (repaymentType != PaymentRepaymentType.SCHEDULED || !postOn.isAfter(transactionDate))) {
            throw new IllegalArgumentException("Chỉ khoản trả kỳ trước ngày đến hạn mới được hẹn ngày ghi core");
        }
        PaymentRepayment value = new PaymentRepayment();
        value.repaymentId = UUID.randomUUID();
        value.idempotencyKey = idempotencyKey;
        value.loanApplicationId = account.getLoanApplicationId();
        value.borrowerId = account.getBorrowerId();
        value.fineractLoanId = account.getFineractLoanId();
        value.amount = amount.setScale(2);
        value.repaymentType = repaymentType;
        value.coreAmount = value.amount;
        value.platformFee = BigDecimal.ZERO.setScale(2);
        value.investorFeeShare = BigDecimal.ZERO.setScale(2);
        value.finoraFeeShare = BigDecimal.ZERO.setScale(2);
        value.currency = account.getCurrency();
        value.transactionDate = transactionDate;
        value.postOn = postOn;
        value.status = PaymentRepaymentStatus.COLLECTED;
        value.walletLedgerTransactionId = walletLedgerTransactionId;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public static PaymentRepayment earlySettlement(String idempotencyKey, PaymentLoanAccount account,
            EarlySettlementQuote quote, UUID walletLedgerTransactionId, Instant now) {
        PaymentRepayment value = new PaymentRepayment();
        value.repaymentId = UUID.randomUUID();
        value.idempotencyKey = idempotencyKey;
        value.loanApplicationId = account.getLoanApplicationId();
        value.borrowerId = account.getBorrowerId();
        value.fineractLoanId = account.getFineractLoanId();
        value.amount = quote.getTotalAmount();
        value.repaymentType = PaymentRepaymentType.EARLY_SETTLEMENT;
        value.quoteId = quote.getQuoteId();
        value.coreAmount = quote.getCoreAmount();
        value.platformFee = quote.getPlatformFee();
        value.investorFeeShare = quote.getInvestorFeeShare();
        value.finoraFeeShare = quote.getFinoraFeeShare();
        value.currency = account.getCurrency();
        value.transactionDate = quote.getTransactionDate();
        value.status = PaymentRepaymentStatus.COLLECTED;
        value.walletLedgerTransactionId = walletLedgerTransactionId;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public static PaymentRepayment partialPrepayment(String idempotencyKey, PaymentLoanAccount account,
            PartialPrepaymentQuote quote, UUID walletLedgerTransactionId, Instant now) {
        PaymentRepayment value = new PaymentRepayment();
        value.repaymentId = UUID.randomUUID();
        value.idempotencyKey = idempotencyKey;
        value.loanApplicationId = account.getLoanApplicationId();
        value.borrowerId = account.getBorrowerId();
        value.fineractLoanId = account.getFineractLoanId();
        value.amount = quote.getTotalAmount();
        value.repaymentType = PaymentRepaymentType.PARTIAL_PREPAYMENT;
        value.partialPrepaymentQuoteId = quote.getQuoteId();
        value.coreAmount = quote.getCoreAmount();
        value.platformFee = quote.getPlatformFee();
        value.investorFeeShare = quote.getInvestorFeeShare();
        value.finoraFeeShare = quote.getFinoraFeeShare();
        value.currency = account.getCurrency();
        value.transactionDate = quote.getTransactionDate();
        value.status = PaymentRepaymentStatus.COLLECTED;
        value.walletLedgerTransactionId = walletLedgerTransactionId;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public void startCorePosting(Instant now) {
        status = PaymentRepaymentStatus.CORE_POSTING;
        attemptCount++;
        updatedAt = now;
    }

    /** Ngày ghi giao dịch vào Fineract: ngày đến hạn với khoản trả kỳ được giữ (D16), còn lại là ngày thu tiền. */
    public LocalDate corePostingDate() {
        return postOn == null ? transactionDate : postOn;
    }

    /** Khoản trả kỳ trước hạn còn nằm ở clearing, chưa tới ngày ghi Fineract. */
    public boolean awaitingDueDate(LocalDate today) {
        return status == PaymentRepaymentStatus.COLLECTED && postOn != null && postOn.isAfter(today);
    }

    /** Người vay hủy khoản trả kỳ đang giữ trước ngày ghi nhận; tiền được hoàn về ví trong cùng transaction. */
    public void cancelBeforeDueDate(LocalDate today, Instant now) {
        if (!awaitingDueDate(today)) {
            throw new IllegalStateException("Chỉ hủy được khoản trả kỳ trước hạn khi chưa tới ngày ghi nhận");
        }
        status = PaymentRepaymentStatus.CANCELLED;
        errorCode = "CANCELLED_BY_BORROWER";
        errorDetail = null;
        updatedAt = now;
    }

    public void corePosted(CoreBreakdown breakdown, Instant now) {
        if (status != PaymentRepaymentStatus.CORE_POSTING
                && status != PaymentRepaymentStatus.RECONCILIATION_REQUIRED) {
            throw new IllegalStateException("Repayment không ở bước ghi hoặc đối soát core");
        }
        requireBreakdown(breakdown);
        fineractTransactionId = breakdown.transactionId();
        principalAmount = breakdown.principal();
        interestAmount = breakdown.interest();
        feeAmount = breakdown.fee().add(platformFee);
        penaltyAmount = breakdown.penalty();
        outstandingPrincipal = breakdown.outstandingPrincipal();
        outstandingInterest = breakdown.outstandingInterest();
        outstandingFee = breakdown.outstandingFee();
        outstandingPenalty = breakdown.outstandingPenalty();
        totalOutstanding = breakdown.totalOutstanding();
        overdueAmount = breakdown.overdueAmount();
        nextDueDate = breakdown.nextDueDate();
        nextDueAmount = breakdown.nextDueAmount();
        status = PaymentRepaymentStatus.CORE_POSTED;
        errorCode = null;
        errorDetail = null;
        updatedAt = now;
    }

    public void complete(UUID distributionLedgerTransactionId, Instant now) {
        this.distributionLedgerTransactionId = distributionLedgerTransactionId;
        status = PaymentRepaymentStatus.COMPLETED;
        completedAt = now;
        updatedAt = now;
    }

    public void requireReconciliation(String code, String detail, Instant now) {
        errorCode = code;
        errorDetail = truncate(detail);
        status = PaymentRepaymentStatus.RECONCILIATION_REQUIRED;
        updatedAt = now;
    }

    /** Khôi phục sau restart/crash giữa lúc đã claim nhưng chưa lưu được kết quả Fineract. */
    public void recoverStaleCorePosting(Instant now) {
        if (status != PaymentRepaymentStatus.CORE_POSTING) {
            return;
        }
        requireReconciliation("CORE_POSTING_INTERRUPTED",
                "Worker dừng hoặc mất kết quả trong lúc ghi Fineract; chỉ được tra cứu theo externalId", now);
    }

    public void fail(String code, String detail, Instant now) {
        errorCode = code;
        errorDetail = truncate(detail);
        status = PaymentRepaymentStatus.FAILED;
        updatedAt = now;
    }

    /**
     * Sớm nhất lúc nào được ghi lại vào Fineract (B32, D28): sau lần ghi hoặc lần chuyển sang đối soát gần nhất một
     * khoảng an toàn, để request cũ chắc chắn đã kết thúc ở Fineract. {@code null} nếu không ở trạng thái đối soát.
     */
    public Instant coreRepostAvailableAt(java.time.Duration safeAfter) {
        return status == PaymentRepaymentStatus.RECONCILIATION_REQUIRED ? updatedAt.plus(safeAfter) : null;
    }

    /**
     * Ghi lại vào Fineract khi tra cứu theo externalId xác nhận core chưa có giao dịch. Chỉ từ RECONCILIATION_REQUIRED;
     * trạng thái về CORE_POSTING như lần ghi thường nên worker phục hồi lần ghi bị ngắt vẫn áp dụng.
     */
    public void startCoreRepost(String actorId, Instant now) {
        if (status != PaymentRepaymentStatus.RECONCILIATION_REQUIRED) {
            throw new IllegalStateException("Chỉ ghi lại được khoản trả nợ đang chờ đối soát");
        }
        if (actorId == null || actorId.isBlank()) throw new IllegalArgumentException("Thiếu người yêu cầu ghi lại");
        status = PaymentRepaymentStatus.CORE_POSTING;
        attemptCount++;
        coreRepostCount++;
        lastCoreRepostBy = actorId;
        lastCoreRepostAt = now;
        updatedAt = now;
    }

    private void requireBreakdown(CoreBreakdown value) {
        BigDecimal sum = value.principal().add(value.interest()).add(value.fee()).add(value.penalty());
        if (sum.compareTo(coreAmount) != 0) {
            throw new IllegalArgumentException("Fineract breakdown không cân với số tiền ghi core");
        }
    }

    private String truncate(String value) {
        return value == null ? null : value.substring(0, Math.min(500, value.length()));
    }

    public record CoreBreakdown(Long transactionId, BigDecimal principal, BigDecimal interest,
            BigDecimal fee, BigDecimal penalty, BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest, BigDecimal outstandingFee, BigDecimal outstandingPenalty,
            BigDecimal totalOutstanding, BigDecimal overdueAmount,
            LocalDate nextDueDate, BigDecimal nextDueAmount) {}
}
