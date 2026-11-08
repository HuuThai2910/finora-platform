package com.finora.loan.service.restructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoanTermExtensionLimitPolicyTest {

    @Test
    void twelveMonthLoanCanExtendAtMostTwelveTerms() {
        var result = LoanTermExtensionLimitPolicy.evaluate(12, 0, 24, 24, 24);

        assertThat(result.currentTotalTerms()).isEqualTo(12);
        assertThat(result.maximumExtraTerms()).isEqualTo(12);
    }

    @Test
    void eighteenMonthLoanCanExtendAtMostSixTerms() {
        var result = LoanTermExtensionLimitPolicy.evaluate(18, 0, 24, 24, 24);

        assertThat(result.maximumExtraTerms()).isEqualTo(6);
    }

    @Test
    void twentyFourMonthLoanCannotExtend() {
        var result = LoanTermExtensionLimitPolicy.evaluate(24, 0, 24, 24, 24);

        assertThat(result.maximumExtraTerms()).isZero();
    }

    @Test
    void completedExtensionsReduceRemainingLimit() {
        var result = LoanTermExtensionLimitPolicy.evaluate(12, 5, 24, 24, 24);

        assertThat(result.currentTotalTerms()).isEqualTo(17);
        assertThat(result.maximumExtraTerms()).isEqualTo(7);
    }
}
