package com.finora.payment.service.repayment;

import com.finora.payment.config.EarlySettlementPolicyProperties;
import com.finora.payment.integration.fineract.EarlySettlementCoreQuote;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

@Component
public class EarlySettlementFeeCalculator {
    private final EarlySettlementPolicyProperties policy;

    public EarlySettlementFeeCalculator(EarlySettlementPolicyProperties policy) {
        this.policy = policy;
    }

    public Fee calculate(EarlySettlementCoreQuote quote) {
        return calculate(quote.principal(), quote.originalTermMonths(), quote.disbursedDate(),
                quote.maturityDate(), quote.transactionDate());
    }

    public Fee calculate(BigDecimal prepaidPrincipal, int term, LocalDate disbursedDate,
            LocalDate maturityDate, LocalDate transactionDate) {
        if (term <= 0 || term > 24) {
            throw new IllegalArgumentException("Thời hạn khoản vay nằm ngoài policy tất toán FINORA (1-24 tháng)");
        }
        BigDecimal rate;
        if (term <= 12) {
            long durationDays = ChronoUnit.DAYS.between(disbursedDate, maturityDate);
            LocalDate midpoint = disbursedDate.plusDays(Math.max(0, durationDays / 2));
            rate = transactionDate.isAfter(midpoint)
                    ? BigDecimal.ZERO : policy.shortTermFirstHalfRate();
        } else {
            rate = policy.longTermRate();
        }
        BigDecimal fee = rate.signum() == 0 ? BigDecimal.ZERO.setScale(2)
                : prepaidPrincipal.multiply(rate).setScale(2, RoundingMode.HALF_UP)
                        .max(policy.minimumFee());
        return new Fee(rate.setScale(6, RoundingMode.HALF_UP), fee, policy.policyVersion());
    }

    public record Fee(BigDecimal rate, BigDecimal amount, String policyVersion) {}
}
