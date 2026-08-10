package com.finora.loan.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoanPurposeTest {

    @Test
    void shouldKeepStableAiMappingAndRequireDetailOnlyForOther() {
        assertThat(LoanPurpose.HOME_IMPROVEMENT.getAiValue()).isEqualTo("home_improvement");
        assertThat(LoanPurpose.EDUCATION.getLabel()).isEqualTo("Chi phí giáo dục");
        assertThat(LoanPurpose.OTHER.isRequiresDetail()).isTrue();
        assertThat(LoanPurpose.DEBT_CONSOLIDATION.isRequiresDetail()).isFalse();
        assertThat(LoanPurpose.values()).hasSize(11);
    }
}
