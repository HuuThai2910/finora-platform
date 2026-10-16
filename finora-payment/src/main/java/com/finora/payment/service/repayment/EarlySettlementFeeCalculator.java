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
            long elapsedDays = Math.max(0, ChronoUnit.DAYS.between(disbursedDate, transactionDate));
            BigDecimal elapsedRatio = durationDays <= 0 ? BigDecimal.ONE
                    : BigDecimal.valueOf(elapsedDays).divide(BigDecimal.valueOf(durationDays), 8, RoundingMode.HALF_UP);
            if (elapsedRatio.compareTo(policy.shortTermFirstBandEnd()) <= 0) {
                rate = policy.shortTermFirstBandRate();
            } else if (elapsedRatio.compareTo(policy.shortTermFreeAfter()) <= 0) {
                rate = policy.shortTermSecondBandRate();
            } else {
                rate = BigDecimal.ZERO;
            }
        } else {
            rate = transactionDate.isBefore(disbursedDate.plusYears(1))
                    ? policy.mediumTermYearOneRate() : policy.mediumTermYearTwoRate();
        }
        BigDecimal fee = rate.signum() == 0 ? BigDecimal.ZERO.setScale(2)
                : prepaidPrincipal.multiply(rate).setScale(2, RoundingMode.HALF_UP)
                        .max(policy.minimumFee());
        BigDecimal investorShare = fee.multiply(policy.investorShareRate())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal finoraShare = fee.subtract(investorShare).setScale(2, RoundingMode.HALF_UP);
        return new Fee(rate.setScale(6, RoundingMode.HALF_UP), fee, investorShare, finoraShare,
                policy.policyVersion());
    }

    /** Snapshot cả tỷ lệ lẫn cách chia để worker không bị ảnh hưởng khi policy runtime đổi. */
    public record Fee(BigDecimal rate, BigDecimal amount, BigDecimal investorShare,
            BigDecimal finoraShare, String policyVersion) {}
}
