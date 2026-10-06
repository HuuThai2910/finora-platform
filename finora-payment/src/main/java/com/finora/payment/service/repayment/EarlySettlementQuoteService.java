package com.finora.payment.service.repayment;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.config.EarlySettlementPolicyProperties;
import com.finora.payment.domain.repayment.EarlySettlementQuote;
import com.finora.payment.domain.servicing.PaymentLoanAccount;
import com.finora.payment.dto.response.EarlySettlementQuoteResponse;
import com.finora.payment.integration.fineract.EarlySettlementCoreQuote;
import com.finora.payment.integration.fineract.RepaymentCoreGateway;
import com.finora.payment.repository.repayment.EarlySettlementQuoteRepository;
import com.finora.payment.repository.servicing.PaymentLoanAccountRepository;
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
public class EarlySettlementQuoteService {
    private final PaymentLoanAccountRepository loanAccounts;
    private final EarlySettlementQuoteRepository quotes;
    private final RepaymentCoreGateway fineract;
    private final EarlySettlementFeeCalculator feeCalculator;
    private final EarlySettlementPolicyProperties policy;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public EarlySettlementQuoteResponse create(Long loanApplicationId) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        QuotePreparation preparation = transactionTemplate.execute(status -> prepare(loanApplicationId, borrowerId));
        if (preparation == null) throw new IllegalStateException("Không chuẩn bị được báo giá tất toán");
        LocalDate today = LocalDate.now(clock);
        EarlySettlementCoreQuote core = fineract.earlySettlementQuote(preparation.fineractLoanId(), today);
        EarlySettlementFeeCalculator.Fee fee = feeCalculator.calculate(core);
        var now = clock.instant();
        EarlySettlementQuote saved = transactionTemplate.execute(status -> {
            PaymentLoanAccount account = requireActiveAccount(loanApplicationId, borrowerId);
            if (!account.getFineractLoanId().equals(preparation.fineractLoanId())) {
                throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_LOAN_CHANGED",
                        "Khoản vay đã thay đổi trong lúc lập báo giá");
            }
            return quotes.save(EarlySettlementQuote.create(account, core, fee.rate(), fee.amount(),
                    fee.policyVersion(), now.plus(policy.quoteTtl()), now));
        });
        if (saved == null) throw new IllegalStateException("Không lưu được báo giá tất toán");
        return EarlySettlementQuoteResponse.from(saved, now);
    }

    @Transactional(readOnly = true)
    public EarlySettlementQuoteResponse get(UUID quoteId) {
        EarlySettlementQuote quote = quotes.findByQuoteId(quoteId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "EARLY_SETTLEMENT_QUOTE_NOT_FOUND",
                        "Không tìm thấy báo giá tất toán"));
        if (!quote.getBorrowerId().equals(SecurityUtils.getCurrentUserId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "EARLY_SETTLEMENT_QUOTE_OWNER_MISMATCH",
                    "Không được xem báo giá của người khác");
        }
        return EarlySettlementQuoteResponse.from(quote, clock.instant());
    }

    private QuotePreparation prepare(Long applicationId, String borrowerId) {
        PaymentLoanAccount account = requireActiveAccount(applicationId, borrowerId);
        return new QuotePreparation(account.getFineractLoanId());
    }

    private PaymentLoanAccount requireActiveAccount(Long applicationId, String borrowerId) {
        PaymentLoanAccount account = loanAccounts.findByLoanApplicationId(applicationId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_LOAN_NOT_FOUND",
                        "Khoản vay chưa sẵn sàng để tất toán"));
        if (!account.getBorrowerId().equals(borrowerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PAYMENT_LOAN_OWNER_MISMATCH",
                    "Khoản vay không thuộc người dùng hiện tại");
        }
        if (account.getStatus() != PaymentLoanAccount.Status.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_LOAN_NOT_ACTIVE",
                    "Khoản vay không còn hoạt động");
        }
        return account;
    }

    private record QuotePreparation(Long fineractLoanId) {}
}
