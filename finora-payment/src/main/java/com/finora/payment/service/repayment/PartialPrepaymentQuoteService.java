package com.finora.payment.service.repayment;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.config.EarlySettlementPolicyProperties;
import com.finora.payment.domain.repayment.PartialPrepaymentQuote;
import com.finora.payment.domain.servicing.PaymentLoanAccount;
import com.finora.payment.dto.response.PartialPrepaymentQuoteResponse;
import com.finora.payment.integration.fineract.FineractQuoteErrors;
import com.finora.payment.integration.fineract.PartialPrepaymentCoreSnapshot;
import com.finora.payment.integration.fineract.RepaymentCoreGateway;
import com.finora.payment.repository.repayment.PartialPrepaymentQuoteRepository;
import com.finora.payment.repository.servicing.PaymentLoanAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class PartialPrepaymentQuoteService {
    private final PaymentLoanAccountRepository loanAccounts;
    private final PartialPrepaymentQuoteRepository quotes;
    private final RepaymentInFlightGuard inFlight;
    private final RepaymentCoreGateway fineract;
    private final EarlySettlementFeeCalculator feeCalculator;
    private final EarlySettlementPolicyProperties policy;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PartialPrepaymentQuoteResponse create(Long applicationId, BigDecimal prepaidPrincipal) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        BigDecimal normalized = prepaidPrincipal.setScale(2);
        QuotePreparation preparation = transactionTemplate.execute(status -> prepare(applicationId, borrowerId));
        if (preparation == null) throw new IllegalStateException("Không chuẩn bị được báo giá trả trước một phần");
        LocalDate today = LocalDate.now(clock);
        PartialPrepaymentCoreSnapshot core = FineractQuoteErrors.read(
                () -> fineract.partialPrepaymentSnapshot(preparation.fineractLoanId(), today));
        if (normalized.compareTo(core.outstandingPrincipal()) >= 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "PARTIAL_PREPAYMENT_AMOUNT_TOO_HIGH",
                    "Phần gốc trả trước phải nhỏ hơn gốc còn lại; dùng luồng tất toán để đóng toàn bộ khoản vay");
        }
        // Biểu phí của chính hợp đồng (snapshot trên tài khoản), không phải biểu phí đang áp cho hợp đồng mới.
        EarlySettlementFeeCalculator.Fee fee = feeCalculator.calculate(preparation.policyVersion(), normalized,
                core.originalTermMonths(), core.disbursedDate(), core.maturityDate(), today);
        var now = clock.instant();
        PartialPrepaymentQuote saved = transactionTemplate.execute(status -> {
            PaymentLoanAccount account = requireEligibleAccount(applicationId, borrowerId);
            if (!account.getFineractLoanId().equals(preparation.fineractLoanId())) {
                throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_LOAN_CHANGED",
                        "Khoản vay đã thay đổi trong lúc lập báo giá");
            }
            try {
                return quotes.save(PartialPrepaymentQuote.create(account, core, normalized, fee.rate(),
                        fee.amount(), fee.investorShare(), fee.finoraShare(), fee.policyVersion(),
                        now.plus(policy.quoteTtl()), now));
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "PARTIAL_PREPAYMENT_AMOUNT_INVALID", exception.getMessage());
            }
        });
        if (saved == null) throw new IllegalStateException("Không lưu được báo giá trả trước một phần");
        return PartialPrepaymentQuoteResponse.from(saved, now);
    }

    @Transactional(readOnly = true)
    public PartialPrepaymentQuoteResponse get(UUID quoteId) {
        PartialPrepaymentQuote quote = quotes.findByQuoteId(quoteId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "PARTIAL_PREPAYMENT_QUOTE_NOT_FOUND", "Không tìm thấy báo giá trả trước một phần"));
        if (!quote.getBorrowerId().equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PARTIAL_PREPAYMENT_QUOTE_OWNER_MISMATCH",
                    "Không được xem báo giá của người khác");
        }
        return PartialPrepaymentQuoteResponse.from(quote, clock.instant());
    }

    private QuotePreparation prepare(Long applicationId, String borrowerId) {
        PaymentLoanAccount account = requireEligibleAccount(applicationId, borrowerId);
        inFlight.requireNoneBeforeQuote(applicationId);
        return new QuotePreparation(account.getFineractLoanId(), account.getEarlySettlementPolicyVersion());
    }

    private PaymentLoanAccount requireEligibleAccount(Long applicationId, String borrowerId) {
        PaymentLoanAccount account = loanAccounts.findByLoanApplicationId(applicationId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_LOAN_NOT_FOUND",
                        "Khoản vay chưa sẵn sàng để trả trước"));
        if (!account.getBorrowerId().equals(borrowerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PAYMENT_LOAN_OWNER_MISMATCH",
                    "Khoản vay không thuộc người dùng hiện tại");
        }
        if (account.getStatus() != PaymentLoanAccount.Status.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_LOAN_NOT_ACTIVE",
                    "Khoản vay không còn hoạt động");
        }
        SelfServicePrepaymentPolicy.requireDailyInterestCore(account);
        return account;
    }

    private record QuotePreparation(Long fineractLoanId, String policyVersion) {}
}
