package com.finora.loan.service.decision.impl;

import com.finora.loan.domain.collection.enums.CollectionCaseStatus;
import com.finora.loan.domain.disbursement.entity.DisbursementSaga;
import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.domain.restructure.enums.LoanRescheduleStatus;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.enums.ApplicationServicingFlag;
import com.finora.loan.domain.servicing.enums.FinoraLoanStatus;
import com.finora.loan.domain.servicing.enums.ReconciliationIncidentStatus;
import com.finora.loan.domain.servicing.model.ApplicationServicingSummary;
import com.finora.loan.repository.collection.LoanCollectionCaseRepository;
import com.finora.loan.repository.disbursement.DisbursementSagaRepository;
import com.finora.loan.repository.restructure.LoanRescheduleRequestRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanReconciliationIncidentRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tình trạng sau giải ngân theo trang hồ sơ: mỗi loại việc đọc một query cho cả trang, cờ gắn đúng hồ sơ, hồ sơ chưa
 * giải ngân thì vắng mặt.
 */
class AdminApplicationServicingServiceImplTest {
    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");

    private final FinoraLoanRepository loans = mock(FinoraLoanRepository.class);
    private final DisbursementSagaRepository sagas = mock(DisbursementSagaRepository.class);
    private final LoanCollectionCaseRepository cases = mock(LoanCollectionCaseRepository.class);
    private final LoanRescheduleRequestRepository reschedules = mock(LoanRescheduleRequestRepository.class);
    private final LoanReconciliationIncidentRepository incidents = mock(LoanReconciliationIncidentRepository.class);
    private final LoanServicingProjectionRepository projections = mock(LoanServicingProjectionRepository.class);
    private final AdminApplicationServicingServiceImpl service = new AdminApplicationServicingServiceImpl(
            loans, sagas, cases, reschedules, incidents, projections);

    @Test
    void flagsEachApplicationFromBatchQueries() {
        FinoraLoan overdue = loan(1L, 11L, "LN-001");
        FinoraLoan stale = loan(2L, 12L, "LN-002");
        DisbursementSaga repair = saga(13L, DisbursementSagaStatus.REPAIR_REQUIRED);
        when(loans.findByLoanApplicationIdIn(anyCollection())).thenReturn(List.of(overdue, stale));
        when(sagas.findByLoanApplicationIdIn(anyCollection())).thenReturn(List.of(repair));
        when(cases.findLoanIdsWithStatus(eq(CollectionCaseStatus.OPEN), anyCollection())).thenReturn(List.of(1L));
        when(reschedules.findLoanIdsWithStatus(eq(LoanRescheduleStatus.PENDING_REVIEW), anyCollection()))
                .thenReturn(List.of(1L));
        when(incidents.findLoanIdsWithStatus(eq(ReconciliationIncidentStatus.OPEN), anyCollection()))
                .thenReturn(List.of());
        when(projections.findStaleLoanIds(anyCollection())).thenReturn(List.of(2L));

        Map<Long, ApplicationServicingSummary> result = service.summariesOf(List.of(11L, 12L, 13L, 14L));

        assertThat(result).containsOnlyKeys(11L, 12L, 13L);
        assertThat(result.get(11L).loanNumber()).isEqualTo("LN-001");
        assertThat(result.get(11L).loanStatus()).isEqualTo(FinoraLoanStatus.ACTIVE);
        assertThat(result.get(11L).flags()).containsExactly(
                ApplicationServicingFlag.OVERDUE, ApplicationServicingFlag.RESCHEDULE_PENDING);
        assertThat(result.get(12L).flags()).containsExactly(ApplicationServicingFlag.INCIDENT);
        // Saga chờ vận hành: chưa có khoản vay nhưng đã có sự cố giải ngân.
        assertThat(result.get(13L).loanNumber()).isNull();
        assertThat(result.get(13L).disbursementStatus()).isEqualTo(DisbursementSagaStatus.REPAIR_REQUIRED);
        assertThat(result.get(13L).flags()).containsExactly(ApplicationServicingFlag.INCIDENT);
        verify(cases).findLoanIdsWithStatus(CollectionCaseStatus.OPEN, Set.of(1L, 2L));
    }

    @Test
    void applicationsWithoutLoanSkipLoanQueries() {
        when(loans.findByLoanApplicationIdIn(anyCollection())).thenReturn(List.of());
        when(sagas.findByLoanApplicationIdIn(anyCollection())).thenReturn(List.of());

        assertThat(service.summariesOf(List.of(21L))).isEmpty();
        assertThat(service.summaryOf(21L)).isEqualTo(ApplicationServicingSummary.NONE);

        verify(cases, never()).findLoanIdsWithStatus(any(), anyCollection());
        verify(projections, never()).findStaleLoanIds(anyCollection());
    }

    @Test
    void emptyPageDoesNotQuery() {
        assertThat(service.summariesOf(List.of())).isEmpty();

        verify(loans, never()).findByLoanApplicationIdIn(anyCollection());
    }

    private static FinoraLoan loan(long id, long applicationId, String loanNumber) {
        FinoraLoan loan = FinoraLoan.activate(loanNumber, applicationId, "LA-" + applicationId, "LC-" + applicationId,
                "borrower-" + applicationId, 100L + id, new BigDecimal("10000000.00"), "VND", NOW);
        ReflectionTestUtils.setField(loan, "id", id);
        return loan;
    }

    private static DisbursementSaga saga(long applicationId, DisbursementSagaStatus status) {
        DisbursementSaga saga = mock(DisbursementSaga.class);
        when(saga.getLoanApplicationId()).thenReturn(applicationId);
        when(saga.getStatus()).thenReturn(status);
        return saga;
    }
}
