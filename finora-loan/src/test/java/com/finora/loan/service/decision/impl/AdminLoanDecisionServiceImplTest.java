package com.finora.loan.service.decision.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.EducationLevel;
import com.finora.loan.domain.application.HomeOwnership;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.domain.core.ScheduleCalculationPurpose;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.decision.response.AdminLoanReviewDetailResponse;
import com.finora.loan.mapper.application.LoanApplicationMapper;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import com.finora.loan.mapper.decision.AdminLoanDecisionMapper;
import com.finora.loan.mapper.scoring.StoredAiCreditResponseReader;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.application.LoanApplicationStatusHistoryRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.scoring.BorrowerCreditProfileRepository;
import com.finora.loan.repository.scoring.BorrowerEligibilityCheckRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import com.finora.loan.service.decision.AdminLoanDecisionStateService;
import com.finora.loan.support.HashingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * B73: chi tiết duyệt hồ sơ trả ID số và số hợp đồng. Số hợp đồng chỉ đọc bằng một query theo khóa duy nhất
 * {@code application_id}; không tải cả Contract (có văn bản hợp đồng) chỉ để lấy một chuỗi.
 */
class AdminLoanDecisionServiceImplTest {

    private static final String NUMBER = "LA-TEST-001";
    private static final long APPLICATION_ID = 7L;
    private static final String CONTRACT_NUMBER = "LC-1A2B3C4D5E6F7A8B9C0D";
    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");

    private final LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
    private final ScheduleCalculationSnapshotRepository schedules = mock(ScheduleCalculationSnapshotRepository.class);
    private final BorrowerEligibilityCheckRepository eligibilities = mock(BorrowerEligibilityCheckRepository.class);
    private final BorrowerCreditProfileRepository creditProfiles = mock(BorrowerCreditProfileRepository.class);
    private final CreditScoringAssessmentRepository assessments = mock(CreditScoringAssessmentRepository.class);
    private final LoanApplicationStatusHistoryRepository histories = mock(LoanApplicationStatusHistoryRepository.class);
    private final LoanContractRepository contracts = mock(LoanContractRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ScheduleCalculationSnapshotMapper scheduleMapper = new ScheduleCalculationSnapshotMapper(objectMapper);
    private final AdminLoanDecisionServiceImpl service = new AdminLoanDecisionServiceImpl(
            applications, schedules, eligibilities, creditProfiles, assessments, histories, contracts,
            mock(AdminLoanDecisionStateService.class), new AdminLoanDecisionMapper(scheduleMapper),
            new LoanApplicationMapper(scheduleMapper), mock(HashingService.class),
            new StoredAiCreditResponseReader(objectMapper));

    @Test
    void reviewDetailReturnsApplicationIdAndContractNumberFromOneKeyedLookup() {
        stubApplication(LoanApplicationStatus.APPROVED);
        when(contracts.findContractNumberByApplicationId(APPLICATION_ID)).thenReturn(Optional.of(CONTRACT_NUMBER));

        AdminLoanReviewDetailResponse detail = service.reviewDetail(NUMBER);

        assertThat(detail.id()).isEqualTo(APPLICATION_ID);
        assertThat(detail.applicationNumber()).isEqualTo(NUMBER);
        assertThat(detail.contractNumber()).isEqualTo(CONTRACT_NUMBER);
        verify(contracts).findContractNumberByApplicationId(APPLICATION_ID);
        verifyNoMoreInteractions(contracts);
    }

    @Test
    void reviewDetailBeforeContractHasIdButNoContractNumber() {
        stubApplication(LoanApplicationStatus.PENDING_REVIEW);
        when(contracts.findContractNumberByApplicationId(APPLICATION_ID)).thenReturn(Optional.empty());

        AdminLoanReviewDetailResponse detail = service.reviewDetail(NUMBER);

        assertThat(detail.id()).isEqualTo(APPLICATION_ID);
        assertThat(detail.status()).isEqualTo(LoanApplicationStatus.PENDING_REVIEW);
        assertThat(detail.contractNumber()).isNull();
    }

    private void stubApplication(LoanApplicationStatus status) {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getId()).thenReturn(APPLICATION_ID);
        when(application.getApplicationNumber()).thenReturn(NUMBER);
        when(application.getBorrowerId()).thenReturn("BORROWER-42");
        when(application.getStatus()).thenReturn(status);
        when(application.getFinancialSnapshot()).thenReturn(ApplicantFinancialSnapshot.capture(
                new BigDecimal("20000000"), 60, EducationLevel.UNIVERSITY, HomeOwnership.RENT,
                new BigDecimal("3000000"), NOW));
        when(applications.findByApplicationNumber(NUMBER)).thenReturn(Optional.of(application));

        ScheduleCalculationSnapshot submission = mock(ScheduleCalculationSnapshot.class);
        when(submission.getPeriodsSnapshotJson()).thenReturn("[]");
        when(schedules.findByApplicationIdAndPurpose(APPLICATION_ID, ScheduleCalculationPurpose.SUBMISSION_SCORING))
                .thenReturn(Optional.of(submission));
        when(schedules.findByApplicationIdAndPurpose(APPLICATION_ID, ScheduleCalculationPurpose.CONTRACT))
                .thenReturn(Optional.empty());
        when(histories.findByLoanApplicationId(eq(APPLICATION_ID), any(Pageable.class))).thenReturn(Page.empty());
    }
}
