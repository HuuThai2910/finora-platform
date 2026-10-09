package com.finora.loan.service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.application.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.EducationLevel;
import com.finora.loan.domain.application.HomeOwnership;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanDecisionSource;
import com.finora.loan.domain.application.LoanFundingStatus;
import com.finora.loan.domain.application.LoanPurpose;
import com.finora.loan.domain.core.ScheduleCalculationPurpose;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.domain.scoring.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import com.finora.loan.domain.scoring.CreditProfileSource;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import com.finora.loan.mapper.application.InvestorBorrowerProfileMapper;
import com.finora.loan.mapper.scoring.StoredAiCreditResponseReader;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.scoring.BorrowerCreditProfileRepository;
import com.finora.loan.repository.scoring.BorrowerEligibilityCheckRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Hồ sơ người vay cho nhà đầu tư: đúng người được xem, đúng hồ sơ được xem, và không bao giờ mang
 * theo SHAP hay định danh dù snapshot chấm điểm có đủ các khối đó.
 */
class InvestorBorrowerProfileServiceTest {

    private static final String NUMBER = "LA-TEST-001";
    private static final Instant NOW = Instant.parse("2026-10-06T05:10:31Z");
    private static final String SNAPSHOT = """
            {"pdProbability":0.3568,"riskScore":95,"evaluationScore":68.92,"creditGrade":"C",
             "recommendation":"PENDING_REVIEW",
             "borrowerExplanation":{"thong_diep":"Hồ sơ của bạn","ly_do_chinh":[],"goi_y_cai_thien":["Giảm nợ"]},
             "modelExplanation":{"gia_tri_co_so":-1.2,"canh_bao":[],"yeu_to_bat_loi":[{"dac_trung":"int_rate"}],
                                 "yeu_to_co_loi":[],"tom_tat":{"bat_loi":[{"ma_nhom":"lai_suat"}],"co_loi":[]}},
             "ruleTrace":[{"ma":"CHARACTER_CIC_HISTORY","mo_ta":"Điểm tín dụng CIC — lịch sử trả nợ",
                           "truong":"cic_score","gia_tri":712,"diem":15,"toi_da":20,"trong_so":1.0,
                           "thieu_du_lieu":false}],
             "rejectionReasons":["CIC_CURRENT_BAD_DEBT"],"modelVersion":"17.0.0",
             "decisionPolicyVersion":"CREDIT_POLICY_V1"}
            """;

    private final LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
    private final ScheduleCalculationSnapshotRepository schedules = mock(ScheduleCalculationSnapshotRepository.class);
    private final BorrowerEligibilityCheckRepository eligibilities = mock(BorrowerEligibilityCheckRepository.class);
    private final BorrowerCreditProfileRepository creditProfiles = mock(BorrowerCreditProfileRepository.class);
    private final CreditScoringAssessmentRepository assessments = mock(CreditScoringAssessmentRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final InvestorBorrowerProfileService service = new InvestorBorrowerProfileService(
            applications, schedules, eligibilities, creditProfiles, assessments,
            new StoredAiCreditResponseReader(objectMapper), new InvestorBorrowerProfileMapper());

    @BeforeEach
    void signInAsInvestor() {
        authenticateAs("ROLE_INVESTOR");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listedApplicationReturnsAnonymousProfileWithoutShap() throws Exception {
        LoanApplication application = listedApplication();
        stubEvidence(application, SNAPSHOT, true);

        InvestorBorrowerProfileResponse response = service.profile(NUMBER);

        assertThat(response.applicationNumber()).isEqualTo(NUMBER);
        assertThat(response.loan().purposeLabel()).isEqualTo("Chi phí giáo dục");
        assertThat(response.loan().maximumInstallment()).isEqualByComparingTo("2730000.00");
        assertThat(response.capacity().dtiSnapshot()).isEqualByComparingTo("15.00");
        assertThat(response.background().age()).isEqualTo(30);
        assertThat(response.background().profileSource()).isEqualTo(BorrowerProfileSource.MOCK_USER_PROFILE);
        assertThat(response.creditHistory().source()).isEqualTo(CreditProfileSource.NO_HISTORY);
        assertThat(response.assessment().creditGrade()).isEqualTo("C");
        assertThat(response.assessment().decisionSource()).isEqualTo(LoanDecisionSource.ADMIN);
        assertThat(response.ruleResults()).singleElement()
                .satisfies(rule -> assertThat(rule.value().intValue()).isEqualTo(712));

        String body = objectMapper.writeValueAsString(response);
        assertThat(body).doesNotContain(
                "modelExplanation", "tom_tat", "yeu_to", "int_rate", "borrowerExplanation", "thong_diep",
                "goi_y_cai_thien", "rejectionReasons", "CIC_CURRENT_BAD_DEBT", "borrowerId", "BORROWER-42",
                "modelVersion");
    }

    @Test
    void applicationNotYetOnMarketLooksLikeMissing() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getFundingStatus()).thenReturn(null);
        when(applications.findByApplicationNumber(NUMBER)).thenReturn(Optional.of(application));

        assertThatThrownBy(() -> service.profile(NUMBER)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(schedules, eligibilities, creditProfiles, assessments);
    }

    @Test
    void unknownApplicationIsNotFound() {
        when(applications.findByApplicationNumber(NUMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.profile(NUMBER)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void borrowerCannotReadAnotherBorrowersProfile() {
        authenticateAs("ROLE_BORROWER");

        assertThatThrownBy(() -> service.profile(NUMBER))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> {
                    assertThat(((BusinessException) error).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((BusinessException) error).getCode()).isEqualTo("INVESTOR_ROLE_REQUIRED");
                });
        verifyNoInteractions(applications);
    }

    @Test
    void corruptSnapshotKeepsProfileButDropsRules() {
        LoanApplication application = listedApplication();
        stubEvidence(application, "{hỏng", true);

        InvestorBorrowerProfileResponse response = service.profile(NUMBER);

        assertThat(response.ruleResults()).isEmpty();
        assertThat(response.assessment().evaluationScore()).isEqualByComparingTo("68.92");
    }

    @Test
    void fallsBackToSubmissionScheduleWhenContractScheduleMissing() {
        LoanApplication application = listedApplication();
        stubEvidence(application, SNAPSHOT, false);

        InvestorBorrowerProfileResponse response = service.profile(NUMBER);

        assertThat(response.loan().firstInstallment()).isEqualByComparingTo("2500000.00");
    }

    private LoanApplication listedApplication() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getId()).thenReturn(7L);
        when(application.getApplicationNumber()).thenReturn(NUMBER);
        when(application.getBorrowerId()).thenReturn("BORROWER-42");
        when(application.getFundingStatus()).thenReturn(LoanFundingStatus.REQUESTED);
        when(application.getPurposeCode()).thenReturn(LoanPurpose.EDUCATION);
        when(application.getPurposeDetail()).thenReturn("Học phí học kỳ I");
        when(application.getRequestedAmount()).thenReturn(new BigDecimal("30000000.00"));
        when(application.getRequestedTermMonths()).thenReturn(12);
        when(application.getRepaymentMethodSnapshot()).thenReturn(RepaymentMethod.ANNUITY);
        when(application.getFinalAnnualInterestRate()).thenReturn(new BigDecimal("15.5000"));
        when(application.getDecisionSource()).thenReturn(LoanDecisionSource.ADMIN);
        when(application.getLatestCreditAssessmentId()).thenReturn(51L);
        when(application.getFinancialSnapshot()).thenReturn(ApplicantFinancialSnapshot.capture(
                new BigDecimal("20000000"), 60, EducationLevel.UNIVERSITY, HomeOwnership.RENT,
                new BigDecimal("3000000"), NOW));
        when(applications.findByApplicationNumber(NUMBER)).thenReturn(Optional.of(application));
        return application;
    }

    private void stubEvidence(LoanApplication application, String snapshotJson, boolean hasContractSchedule) {
        ScheduleCalculationSnapshot contract = schedule("2730000.00");
        ScheduleCalculationSnapshot submission = schedule("2500000.00");
        when(schedules.findByApplicationIdAndPurpose(7L, ScheduleCalculationPurpose.CONTRACT))
                .thenReturn(hasContractSchedule ? Optional.of(contract) : Optional.empty());
        when(schedules.findByApplicationIdAndPurpose(7L, ScheduleCalculationPurpose.SUBMISSION_SCORING))
                .thenReturn(Optional.of(submission));

        BorrowerEligibilityCheck eligibility = mock(BorrowerEligibilityCheck.class);
        when(eligibility.getAge()).thenReturn(30);
        when(eligibility.getKycStatus()).thenReturn(BorrowerKycStatus.VERIFIED);
        when(eligibility.getProfileSource()).thenReturn(BorrowerProfileSource.MOCK_USER_PROFILE);
        when(eligibilities.findFirstByApplicationIdOrderByCreatedAtDescIdDesc(7L))
                .thenReturn(Optional.of(eligibility));

        BorrowerCreditProfile creditProfile = mock(BorrowerCreditProfile.class);
        when(creditProfile.getSource()).thenReturn(CreditProfileSource.NO_HISTORY);
        when(creditProfiles.findByBorrowerId(application.getBorrowerId())).thenReturn(Optional.of(creditProfile));

        CreditScoringAssessment assessment = mock(CreditScoringAssessment.class);
        when(assessment.getId()).thenReturn(51L);
        when(assessment.getStatus()).thenReturn(CreditAssessmentStatus.SUCCEEDED);
        when(assessment.getEvaluationScore()).thenReturn(new BigDecimal("68.92"));
        when(assessment.getCreditGrade()).thenReturn("C");
        when(assessment.getPdProbability()).thenReturn(new BigDecimal("0.3568"));
        when(assessment.getRiskScore()).thenReturn(95);
        when(assessment.getResponseSnapshotJson()).thenReturn(snapshotJson);
        when(assessments.findByIdAndApplicationId(51L, 7L)).thenReturn(Optional.of(assessment));
    }

    private static ScheduleCalculationSnapshot schedule(String installment) {
        ScheduleCalculationSnapshot schedule = mock(ScheduleCalculationSnapshot.class);
        when(schedule.getFirstInstallment()).thenReturn(new BigDecimal(installment));
        when(schedule.getMaximumInstallment()).thenReturn(new BigDecimal(installment));
        when(schedule.getTotalRepayment()).thenReturn(new BigDecimal("32760000.00"));
        when(schedule.getExpectedDisbursementDate()).thenReturn(LocalDate.of(2026, 10, 20));
        return schedule;
    }

    private static void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", null, role));
    }
}
