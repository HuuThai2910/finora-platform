package com.finora.payment.dto.response;

import com.finora.payment.domain.repayment.EarlySettlementQuote;
import com.finora.payment.domain.repayment.EarlySettlementQuoteStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EarlySettlementQuoteResponse(UUID quoteId, Long loanApplicationId, String currency,
        LocalDate transactionDate, BigDecimal principalPortion, BigDecimal interestPortion,
        BigDecimal coreFeePortion, BigDecimal penaltyPortion, BigDecimal coreAmount,
        BigDecimal platformFee, BigDecimal totalAmount, BigDecimal feeRate,
        String policyVersion, EarlySettlementQuoteStatus status, Instant expiresAt) {
    public static EarlySettlementQuoteResponse from(EarlySettlementQuote value, Instant now) {
        EarlySettlementQuoteStatus visibleStatus = value.isExpired(now)
                ? EarlySettlementQuoteStatus.EXPIRED : value.getStatus();
        return new EarlySettlementQuoteResponse(value.getQuoteId(), value.getLoanApplicationId(), value.getCurrency(),
                value.getTransactionDate(), value.getPrincipalPortion(), value.getInterestPortion(),
                value.getCoreFeePortion(), value.getPenaltyPortion(), value.getCoreAmount(), value.getPlatformFee(),
                value.getTotalAmount(), value.getFeeRate(), value.getPolicyVersion(), visibleStatus,
                value.getExpiresAt());
    }
}
