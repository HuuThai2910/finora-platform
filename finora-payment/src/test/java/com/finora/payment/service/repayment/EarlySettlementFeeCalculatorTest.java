package com.finora.payment.service.repayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.common.exception.BusinessException;
import com.finora.payment.integration.fineract.EarlySettlementCoreQuote;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EarlySettlementFeeCalculatorTest {
    private static final String V2 = EarlySettlementPolicyCatalog.AGRIBANK_REFERENCE_FINORA_2026_V2;

    private final EarlySettlementFeeCalculator calculator = new EarlySettlementFeeCalculator();

    @Test
    void appliesOnePercentAndSplitsFeeDuringFirstHalfOfShortTermLoan() {
        var result = calculator.calculate(V2, quote(12, LocalDate.of(2026, 12, 1), "10000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.010000");
        assertThat(result.amount()).isEqualByComparingTo("100000.00");
        assertThat(result.investorShare()).isEqualByComparingTo("80000.00");
        assertThat(result.finoraShare()).isEqualByComparingTo("20000.00");
        assertThat(result.policyVersion()).isEqualTo(V2);
    }

    @Test
    void appliesHalfPercentBetweenHalfAndSeventyPercent() {
        var result = calculator.calculate(V2, quote(12, LocalDate.of(2027, 5, 1), "40000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.005000");
        assertThat(result.amount()).isEqualByComparingTo("200000.00");
    }

    @Test
    void waivesFeeAfterSeventyPercentOfShortTermLoan() {
        var result = calculator.calculate(V2, quote(12, LocalDate.of(2027, 7, 1), "10000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.000000");
        assertThat(result.amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void appliesTwoPercentDuringFirstYearOfMediumTermLoan() {
        var result = calculator.calculate(V2, quote(24, LocalDate.of(2027, 5, 1), "30000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.020000");
        assertThat(result.amount()).isEqualByComparingTo("600000.00");
    }

    @Test
    void appliesOneAndHalfPercentDuringSecondYearOfMediumTermLoan() {
        var result = calculator.calculate(V2, quote(24, LocalDate.of(2027, 11, 1), "30000000"));

        assertThat(result.rate()).isEqualByComparingTo("0.015000");
        assertThat(result.amount()).isEqualByComparingTo("450000.00");
    }

    @Test
    void appliesMinimumFeeWhenPercentageIsSmaller() {
        // 1% của 8.375.000 là 83.750, nhỏ hơn mức tối thiểu 100.000 đã ghi trong hợp đồng V2.
        var result = calculator.calculate(V2, quote(6, LocalDate.of(2026, 11, 15), "8375000"));

        assertThat(result.amount()).isEqualByComparingTo("100000.00");
        assertThat(result.investorShare()).isEqualByComparingTo("80000.00");
    }

    @Test
    void contractWithoutDisclosedFeeScheduleIsNeverCharged() {
        // Tài khoản cũ (NULL) và hợp đồng LOAN_TERMS_V1 không ghi phí trả trước: cùng ngày, cùng số tiền nhưng phí 0.
        var legacy = calculator.calculate(null, quote(12, LocalDate.of(2026, 12, 1), "10000000"));
        var explicit = calculator.calculate(EarlySettlementPolicyCatalog.NO_FEE_DISCLOSED,
                quote(24, LocalDate.of(2027, 5, 1), "30000000"));

        assertThat(legacy.amount()).isEqualByComparingTo("0.00");
        assertThat(legacy.investorShare()).isEqualByComparingTo("0.00");
        assertThat(legacy.policyVersion()).isEqualTo(EarlySettlementPolicyCatalog.NO_FEE_DISCLOSED);
        assertThat(explicit.amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void unknownPolicyVersionStopsTheQuoteInsteadOfGuessing() {
        assertThatThrownBy(() -> calculator.calculate("AGRIBANK_REFERENCE_FINORA_2027_V3",
                quote(12, LocalDate.of(2026, 12, 1), "10000000")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AGRIBANK_REFERENCE_FINORA_2027_V3");
    }

    private EarlySettlementCoreQuote quote(int term, LocalDate transactionDate, String principal) {
        BigDecimal p = new BigDecimal(principal).setScale(2);
        return new EarlySettlementCoreQuote(transactionDate, p, p, BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), term,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1).plusMonths(term));
    }
}
