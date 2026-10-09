package com.finora.loan.mapper.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.EducationLevel;
import com.finora.loan.domain.application.HomeOwnership;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanPurpose;
import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse.RuleResult;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Bảng luật là dữ liệu admin cấu hình nên có thể chứa field lạ; mapper chỉ được chép đúng các field
 * đã duyệt cho nhà đầu tư.
 */
class InvestorBorrowerProfileMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-06T05:10:31Z");
    private final InvestorBorrowerProfileMapper mapper = new InvestorBorrowerProfileMapper();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void copiesOnlyAllowlistedRuleFields() throws Exception {
        JsonNode trace = json.readTree("""
                [
                  {"ma":"CHARACTER_CIC_HISTORY","mo_ta":"Điểm tín dụng CIC — lịch sử trả nợ","truong":"cic_score",
                   "gia_tri":712,"diem":15,"toi_da":20,"trong_so":1.0,"thieu_du_lieu":false,"goi_y":"bí mật"},
                  {"ma":"X","mo_ta":"Lồng","truong":"x","gia_tri":{"so_cccd":"0790"},"diem":8,"toi_da":20,
                   "trong_so":2,"thieu_du_lieu":true},
                  "khong-phai-object"
                ]
                """);

        InvestorBorrowerProfileResponse response = mapper.toResponse(
                application(), null, null, null, assessment(CreditAssessmentStatus.SUCCEEDED), trace);

        assertThat(response.ruleResults()).hasSize(2);
        RuleResult first = response.ruleResults().getFirst();
        assertThat(first.code()).isEqualTo("CHARACTER_CIC_HISTORY");
        assertThat(first.field()).isEqualTo("cic_score");
        assertThat(first.value().intValue()).isEqualTo(712);
        assertThat(first.points()).isEqualTo(15);
        assertThat(first.maxPoints()).isEqualTo(20);
        assertThat(first.missingData()).isFalse();
        RuleResult nested = response.ruleResults().get(1);
        assertThat(nested.value().isNull()).isTrue();
        assertThat(nested.weight()).isEqualByComparingTo("2");
        assertThat(nested.missingData()).isTrue();
        assertThat(json.writeValueAsString(response.ruleResults())).doesNotContain("goi_y", "so_cccd", "bí mật");
    }

    @Test
    void unsuccessfulScoringPublishesNoScoreAndNoRules() throws Exception {
        JsonNode trace = json.readTree("[{\"ma\":\"R1\",\"mo_ta\":\"Luật\",\"truong\":\"dti\",\"gia_tri\":5}]");

        InvestorBorrowerProfileResponse response = mapper.toResponse(
                application(), null, null, null, assessment(CreditAssessmentStatus.FAILED), trace);

        assertThat(response.assessment()).isNull();
        assertThat(response.ruleResults()).isEmpty();
        assertThat(response.background().age()).isNull();
        assertThat(response.background().homeOwnership()).isEqualTo(HomeOwnership.RENT);
        assertThat(response.loan().purposeLabel()).isEqualTo("Chi phí giáo dục");
        assertThat(response.loan().firstInstallment()).isNull();
        assertThat(response.creditHistory()).isNull();
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
