package com.finora.payment.service.repayment.impl;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.config.EarlySettlementPolicyProperties;
import com.finora.payment.domain.repayment.entity.EarlySettlementQuote;
import com.finora.payment.domain.servicing.entity.PaymentLoanAccount;
import com.finora.payment.dto.response.EarlySettlementQuoteResponse;
import com.finora.payment.integration.fineract.FineractQuoteErrors;
import com.finora.payment.integration.fineract.client.RepaymentCoreGateway;
import com.finora.payment.integration.fineract.contract.EarlySettlementCoreQuote;
import com.finora.payment.repository.repayment.EarlySettlementQuoteRepository;
import com.finora.payment.repository.servicing.PaymentLoanAccountRepository;
import com.finora.payment.service.repayment.EarlySettlementFeeCalculator;
import com.finora.payment.service.repayment.EarlySettlementQuoteService;
import com.finora.payment.service.repayment.RepaymentInFlightGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Báo giá tất toán khoản vay cho người vay.
 * <p>
 * Ba bước: transaction ngắn kiểm tài khoản thu nợ (chỉ core V4, không còn khoản đang chờ ghi Fineract), đọc báo giá
 * Fineract ngoài transaction, rồi transaction thứ hai kiểm khoản vay chưa đổi và lưu báo giá với phí theo biểu phí của
 * hợp đồng, hạn dùng {@code finora.payment.early-settlement.quote-ttl}.
 */
@Service
@RequiredArgsConstructor
public class EarlySettlementQuoteServiceImpl implements EarlySettlementQuoteService {
    private final PaymentLoanAccountRepository paymentLoanAccountRepository;
    private final EarlySettlementQuoteRepository earlySettlementQuoteRepository;
    private final RepaymentInFlightGuard repaymentInFlightGuard;
    private final RepaymentCoreGateway repaymentCoreGateway;
    private final EarlySettlementFeeCalculator earlySettlementFeeCalculator;
    private final EarlySettlementPolicyProperties earlySettlementPolicyProperties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Override
    public EarlySettlementQuoteResponse create(Long loanApplicationId) {
        String borrowerId = SecurityUtils.getCurrentUserId();
        QuotePreparation preparation = transactionTemplate.execute(status -> prepare(loanApplicationId, borrowerId));
        if (preparation == null) throw new IllegalStateException("Không chuẩn bị được báo giá tất toán");
        LocalDate today = LocalDate.now(clock);
        EarlySettlementCoreQuote core = FineractQuoteErrors.read(
                () -> repaymentCoreGateway.earlySettlementQuote(preparation.fineractLoanId(), today));
        // Biểu phí của chính hợp đồng (snapshot trên tài khoản), không phải biểu phí đang áp cho hợp đồng mới.
        EarlySettlementFeeCalculator.Fee fee =
                earlySettlementFeeCalculator.calculate(preparation.policyVersion(), core);
        var now = clock.instant();
        EarlySettlementQuote saved = transactionTemplate.execute(status -> {
            PaymentLoanAccount account = requireActiveAccount(loanApplicationId, borrowerId);
            if (!account.getFineractLoanId().equals(preparation.fineractLoanId())) {
                throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_LOAN_CHANGED",
                        "Khoản vay đã thay đổi trong lúc lập báo giá");
            }
            return earlySettlementQuoteRepository.save(EarlySettlementQuote.create(account, core, fee.rate(),
                    fee.amount(), fee.investorShare(), fee.finoraShare(), fee.policyVersion(),
                    now.plus(earlySettlementPolicyProperties.quoteTtl()), now));
        });
        if (saved == null) throw new IllegalStateException("Không lưu được báo giá tất toán");
        return EarlySettlementQuoteResponse.from(saved, now);
    }

    @Override
    @Transactional(readOnly = true)
    public EarlySettlementQuoteResponse get(UUID quoteId) {
        EarlySettlementQuote quote = earlySettlementQuoteRepository.findByQuoteId(quoteId)
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
        // D17: core V1–V3 báo giá tất toán gồm cả lãi các tháng tương lai (B29), không cho tự tất toán.
        SelfServicePrepaymentPolicy.requireDailyInterestCore(account);
        repaymentInFlightGuard.requireNoneBeforeQuote(applicationId);
        return new QuotePreparation(account.getFineractLoanId(), account.getEarlySettlementPolicyVersion());
    }

    private PaymentLoanAccount requireActiveAccount(Long applicationId, String borrowerId) {
        PaymentLoanAccount account = paymentLoanAccountRepository.findByLoanApplicationId(applicationId)
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

    private record QuotePreparation(Long fineractLoanId, String policyVersion) {}
}
