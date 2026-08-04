package com.finora.loan.domain;

import com.finora.loan.exception.LoanBusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicantFinancialSnapshotTest {

    @Test
    void shouldCaptureAlternativeDataAndCalculateAnnualIncomeAndDti() {
        ApplicantFinancialSnapshot snapshot = ApplicantFinancialSnapshot.capture(
                new BigDecimal("20000000"), 60, EducationLevel.UNIVERSITY,
                HomeOwnership.RENT, new BigDecimal("3000000"), Instant.parse("2026-08-02T08:00:00Z"));

        assertThat(snapshot.getAnnualIncomeSnapshot()).isEqualByComparingTo("240000000.00");
        assertThat(snapshot.getDtiSnapshot()).isEqualByComparingTo("15.0000");
        assertThat(snapshot.getInformationSource()).isEqualTo(CreditInformationSource.SELF_DECLARED);
    }

    @Test
    void shouldRejectNegativeDebt() {
        assertThatThrownBy(() -> ApplicantFinancialSnapshot.capture(
                new BigDecimal("20000000"), 12, null,
                HomeOwnership.OWN, new BigDecimal("-1"), Instant.now()))
                .isInstanceOf(LoanBusinessException.class)
                .extracting("code")
                .isEqualTo("MONTHLY_DEBT_OBLIGATIONS_INVALID");
    }
}
