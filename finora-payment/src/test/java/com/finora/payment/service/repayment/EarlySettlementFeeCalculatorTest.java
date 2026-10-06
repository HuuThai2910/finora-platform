package com.finora.payment.service.repayment;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.payment.config.EarlySettlementPolicyProperties;
import com.finora.payment.integration.fineract.EarlySettlementCoreQuote;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EarlySettlementFeeCalculatorTest {
    private final EarlySettlementFeeCalculator calculator = new EarlySettlementFeeCalculator(
            new EarlySettlementPolicyProperties("VCB_REFERENCE_2024_V1", Duration.ofMinutes(15),
                    new BigDecimal("0.005"), new BigDecimal("0.01"), new BigDecimal("100000")));

    @Test
    void appliesMinimumFeeDuringFirstHalfOfShortTermLoan() {
        var result = calculator.calculate(quote(12, LocalDate.of(2026, 12, 1), "10000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.005000");
        assertThat(result.amount()).isEqualByComparingTo("100000.00");
    }

    @Test
    void waivesFeeAfterFirstHalfOfShortTermLoan() {
        var result = calculator.calculate(quote(12, LocalDate.of(2027, 5, 1), "10000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.000000");
        assertThat(result.amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void appliesOnePercentForLongTermLoan() {
        var result = calculator.calculate(quote(24, LocalDate.of(2027, 5, 1), "30000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.010000");
        assertThat(result.amount()).isEqualByComparingTo("300000.00");
    }

    private EarlySettlementCoreQuote quote(int term, LocalDate transactionDate, String principal) {
        BigDecimal p = new BigDecimal(principal).setScale(2);
        return new EarlySettlementCoreQuote(transactionDate, p, p, BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), term,
                LocalDate.of(2026, 10, 1), LocalDate.of(2027, 10, 1));
    }
}
