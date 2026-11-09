package com.finora.loan.mapper.application;

import com.finora.loan.domain.application.entity.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.EducationLevel;
import com.finora.loan.domain.application.enums.HomeOwnership;
import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.domain.scoring.entity.CreditScoringAssessment;
import com.finora.loan.domain.scoring.enums.CreditAssessmentStatus;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Mapper chỉ công bố điểm tổng hợp khi lần chấm thành công; thiếu snapshot thì để trống, không bịa. */
class InvestorBorrowerProfileMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-06T05:10:31Z");
    private final InvestorBorrowerProfileMapper mapper = new InvestorBorrowerProfileMapper();

    @Test
    void unsuccessfulScoringPublishesNoScore() {
        InvestorBorrowerProfileResponse response = mapper.toResponse(
                application(), null, null, assessment(CreditAssessmentStatus.FAILED));

        assertThat(response.assessment()).isNull();
        assertThat(response.background().age()).isNull();
        assertThat(response.background().homeOwnership()).isEqualTo(HomeOwnership.RENT);
        assertThat(response.loan().purposeLabel()).isEqualTo("Chi phí giáo dục");
        assertThat(response.loan().firstInstallment()).isNull();
    }

    private static LoanApplication application() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getApplicationNumber()).thenReturn("LA-TEST-001");
        when(application.getPurposeCode()).thenReturn(LoanPurpose.EDUCATION);
        when(application.getRequestedAmount()).thenReturn(new BigDecimal("30000000.00"));
        when(application.getRequestedTermMonths()).thenReturn(12);
        when(application.getFinancialSnapshot()).thenReturn(ApplicantFinancialSnapshot.capture(
                new BigDecimal("20000000"), 60, EducationLevel.UNIVERSITY, HomeOwnership.RENT,
                new BigDecimal("3000000"), NOW));
        return application;
    }

    private static CreditScoringAssessment assessment(CreditAssessmentStatus status) {
        CreditScoringAssessment assessment = mock(CreditScoringAssessment.class);
        when(assessment.getStatus()).thenReturn(status);
        when(assessment.getEvaluationScore()).thenReturn(new BigDecimal("68.92"));
        return assessment;
    }
}
