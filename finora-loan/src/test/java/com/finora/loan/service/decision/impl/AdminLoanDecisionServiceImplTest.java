package com.finora.loan.service.decision.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.application.entity.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.EducationLevel;
import com.finora.loan.domain.application.enums.HomeOwnership;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.core.enums.ScheduleCalculationPurpose;
import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.domain.servicing.enums.ApplicationServicingFlag;
import com.finora.loan.domain.servicing.enums.FinoraLoanStatus;
import com.finora.loan.domain.servicing.model.ApplicationServicingSummary;
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
import com.finora.loan.service.application.LoanApplicationTimelineService;
import com.finora.loan.service.decision.AdminApplicationServicingService;
import com.finora.loan.service.decision.AdminLoanDecisionStateService;
import com.finora.loan.util.HashingService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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
    private final AdminApplicationServicingService servicing = mock(AdminApplicationServicingService.class);
    private final AdminLoanDecisionServiceImpl service = new AdminLoanDecisionServiceImpl(
            applications, schedules, eligibilities, creditProfiles, assessments, histories, contracts,
            mock(AdminLoanDecisionStateService.class), new AdminLoanDecisionMapper(scheduleMapper),
            new LoanApplicationMapper(scheduleMapper), mock(HashingService.class),
            new StoredAiCreditResponseReader(objectMapper), mock(LoanApplicationTimelineService.class), servicing);

    @Test
    void reviewDetailReturnsApplicationIdAndContractNumberFromOneKeyedLookup() {
        stubApplication(LoanApplicationStatus.APPROVED);
        LoanContractRepository.ContractCodeView reference = mock(LoanContractRepository.ContractCodeView.class);
        when(reference.getContractNumber()).thenReturn(CONTRACT_NUMBER);
        when(reference.getContractCode()).thenReturn("HĐ-20261109-K7QM");
        when(contracts.findContractReferenceByApplicationId(APPLICATION_ID)).thenReturn(Optional.of(reference));

        AdminLoanReviewDetailResponse detail = service.reviewDetail(NUMBER);

        assertThat(detail.id()).isEqualTo(APPLICATION_ID);
        assertThat(detail.applicationNumber()).isEqualTo(NUMBER);
        assertThat(detail.contractNumber()).isEqualTo(CONTRACT_NUMBER);
        assertThat(detail.contractCode()).isEqualTo("HĐ-20261109-K7QM");
        verify(contracts).findContractReferenceByApplicationId(APPLICATION_ID);
        verifyNoMoreInteractions(contracts);
    }

    @Test
    void searchMatchesDisplayAndLookupCodesThroughOneQuery() {
        when(applications.searchAdmin(eq(LoanApplicationStatus.APPROVED), eq(null), eq("%HĐ-20261109%"),
                eq("hd-20261109"), any(Pageable.class))).thenReturn(Page.empty());

        service.listApplications(LoanApplicationStatus.APPROVED, null, " hd-20261109 ", 0, 20);

        verify(applications).searchAdmin(eq(LoanApplicationStatus.APPROVED), eq(null), eq("%HĐ-20261109%"),
                eq("hd-20261109"), any(Pageable.class));
    }

    @Test
    void blankSearchKeepsTheOriginalStatusQuery() {
        when(applications.findByStatus(eq(LoanApplicationStatus.PENDING_REVIEW), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.listApplications(LoanApplicationStatus.PENDING_REVIEW, null, "  ", 0, 20);

        verify(applications).findByStatus(eq(LoanApplicationStatus.PENDING_REVIEW), any(Pageable.class));
    }

    @Test
    void servicingFilterUsesExistsQueryAndMatchesEveryCodeWithoutSearch() {
        when(applications.searchAdminByServicing(eq(null), eq(null), eq("%"), eq(""), eq(true), eq(false), eq(false),
                any(Pageable.class))).thenReturn(Page.empty());

        service.listApplications(null, null, null, ApplicationServicingFlag.OVERDUE, 0, 20);

        verify(applications).searchAdminByServicing(eq(null), eq(null), eq("%"), eq(""), eq(true), eq(false),
                eq(false), any(Pageable.class));
    }

    @Test
    void servicingFilterKeepsSearchCodeAndStatus() {
        when(applications.searchAdminByServicing(eq(LoanApplicationStatus.APPROVED), eq(null), eq("%HS-2026%"),
                eq("hs-2026"), eq(false), eq(true), eq(false), any(Pageable.class))).thenReturn(Page.empty());

        service.listApplications(LoanApplicationStatus.APPROVED, null, "hs-2026",
                ApplicationServicingFlag.RESCHEDULE_PENDING, 0, 20);

        verify(applications).searchAdminByServicing(eq(LoanApplicationStatus.APPROVED), eq(null), eq("%HS-2026%"),
                eq("hs-2026"), eq(false), eq(true), eq(false), any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void advancedFilterGoesThroughOneSpecificationQuery() {
        when(applications.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());
        var filter = new com.finora.loan.dto.decision.request.AdminApplicationFilter(null, null,
                new BigDecimal("10000000"), null, null, null, null, null, List.of("A"), null, null, null, null);

        service.listApplications(null, null, "hs-2026", ApplicationServicingFlag.INCIDENT, filter, 0, 20);

        verify(applications).findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class));
        verify(applications, org.mockito.Mockito.never()).searchAdminByServicing(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void invalidRangeIsRejectedBeforeAnyQuery() {
        var filter = new com.finora.loan.dto.decision.request.AdminApplicationFilter(null, null, null, null, 24, 6,
                null, null, null, null, null, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.listApplications(null, null, null, null, filter, 0, 20))
                .isInstanceOfSatisfying(com.finora.common.exception.BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("FILTER_RANGE_INVALID"));
        verifyNoMoreInteractions(applications);
    }

    @Test
    void listRowsCarryLoanNumberAndServicingFlags() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getId()).thenReturn(APPLICATION_ID);
        when(application.getApplicationNumber()).thenReturn(NUMBER);
        when(applications.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(application)));
        when(servicing.summariesOf(List.of(APPLICATION_ID))).thenReturn(Map.of(APPLICATION_ID,
                new ApplicationServicingSummary("LN-7", FinoraLoanStatus.ACTIVE, DisbursementSagaStatus.COMPLETED,
                        List.of(ApplicationServicingFlag.OVERDUE))));

        var page = service.listApplications(null, null, null, null, 0, 20);

        assertThat(page.data()).singleElement().satisfies(row -> {
            assertThat(row.loanNumber()).isEqualTo("LN-7");
            assertThat(row.servicingFlags()).containsExactly(ApplicationServicingFlag.OVERDUE);
        });
    }

    @Test
    void reviewDetailCarriesLoanOfTheApplication() {
        stubApplication(LoanApplicationStatus.APPROVED);
        when(contracts.findContractReferenceByApplicationId(APPLICATION_ID)).thenReturn(Optional.empty());
        when(servicing.summaryOf(APPLICATION_ID)).thenReturn(new ApplicationServicingSummary(
                "LN-7", FinoraLoanStatus.ACTIVE, DisbursementSagaStatus.COMPLETED,
                List.of(ApplicationServicingFlag.INCIDENT)));

        AdminLoanReviewDetailResponse detail = service.reviewDetail(NUMBER);

        assertThat(detail.loanNumber()).isEqualTo("LN-7");
        assertThat(detail.loanStatus()).isEqualTo(FinoraLoanStatus.ACTIVE);
        assertThat(detail.disbursementStatus()).isEqualTo(DisbursementSagaStatus.COMPLETED);
        assertThat(detail.servicingFlags()).containsExactly(ApplicationServicingFlag.INCIDENT);
    }

    @Test
    void reviewDetailBeforeContractHasIdButNoContractNumber() {
        stubApplication(LoanApplicationStatus.PENDING_REVIEW);
        when(contracts.findContractReferenceByApplicationId(APPLICATION_ID)).thenReturn(Optional.empty());

        AdminLoanReviewDetailResponse detail = service.reviewDetail(NUMBER);

        assertThat(detail.id()).isEqualTo(APPLICATION_ID);
        assertThat(detail.status()).isEqualTo(LoanApplicationStatus.PENDING_REVIEW);
        assertThat(detail.contractNumber()).isNull();
        assertThat(detail.contractCode()).isNull();
        assertThat(detail.loanNumber()).isNull();
        assertThat(detail.servicingFlags()).isEmpty();
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
