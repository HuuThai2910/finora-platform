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
            new EarlySettlementPolicyProperties("AGRIBANK_REFERENCE_FINORA_2026_V2", Duration.ofMinutes(15),
                    new BigDecimal("0.01"), new BigDecimal("0.005"), new BigDecimal("0.50"),
                    new BigDecimal("0.70"), new BigDecimal("0.02"), new BigDecimal("0.015"),
                    new BigDecimal("100000"), new BigDecimal("0.80")));

    @Test
    void appliesOnePercentAndSplitsFeeDuringFirstHalfOfShortTermLoan() {
        var result = calculator.calculate(quote(12, LocalDate.of(2026, 12, 1), "10000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.010000");
        assertThat(result.amount()).isEqualByComparingTo("100000.00");
        assertThat(result.investorShare()).isEqualByComparingTo("80000.00");
        assertThat(result.finoraShare()).isEqualByComparingTo("20000.00");
    }

    @Test
    void appliesHalfPercentBetweenHalfAndSeventyPercent() {
        var result = calculator.calculate(quote(12, LocalDate.of(2027, 5, 1), "40000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.005000");
        assertThat(result.amount()).isEqualByComparingTo("200000.00");
    }

    @Test
    void waivesFeeAfterSeventyPercentOfShortTermLoan() {
        var result = calculator.calculate(quote(12, LocalDate.of(2027, 7, 1), "10000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.000000");
        assertThat(result.amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void appliesTwoPercentDuringFirstYearOfMediumTermLoan() {
        var result = calculator.calculate(quote(24, LocalDate.of(2027, 5, 1), "30000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.020000");
        assertThat(result.amount()).isEqualByComparingTo("600000.00");
    }

    @Test
    void appliesOneAndHalfPercentDuringSecondYearOfMediumTermLoan() {
        var result = calculator.calculate(quote(24, LocalDate.of(2027, 11, 1), "30000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.015000");
        assertThat(result.amount()).isEqualByComparingTo("450000.00");
    }

    private EarlySettlementCoreQuote quote(int term, LocalDate transactionDate, String principal) {
        BigDecimal p = new BigDecimal(principal).setScale(2);
        return new EarlySettlementCoreQuote(transactionDate, p, p, BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), term,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1).plusMonths(term));
    }
}
