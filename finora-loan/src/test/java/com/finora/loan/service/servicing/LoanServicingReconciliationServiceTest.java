package com.finora.loan.service.servicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.finora.loan.domain.servicing.*;
import com.finora.loan.repository.servicing.LoanReconciliationIncidentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LoanServicingReconciliationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T06:00:00Z");
    @Mock private LoanReconciliationIncidentRepository incidents;
    private LoanServicingReconciliationService service;
    private FinoraLoan loan;

    @BeforeEach
    void setUp() {
        service = new LoanServicingReconciliationService(incidents);
        loan = FinoraLoan.activate("LN-001", 9L, "APP-001", "LC-001", "borrower-1",
                88L, money("10000000"), "VND", NOW.minusSeconds(86400));
        ReflectionTestUtils.setField(loan, "id", 1L);
    }

    @Test
    void validSnapshotResolvesPriorIncidentsAndCanRefreshProjection() {
        LoanReconciliationIncident open = LoanReconciliationIncident.open(1L, "LN-001",
                ReconciliationIncidentType.EXTERNAL_ID_MISMATCH, "LC-001", "wrong", NOW.minusSeconds(60));
        when(incidents.findOpenByLoanIdForUpdate(1L)).thenReturn(List.of(open));

        var result = service.inspect(loan, validSnapshot(), NOW);

        assertThat(result).isEmpty();
        assertThat(open.getStatus()).isEqualTo(ReconciliationIncidentStatus.RESOLVED);
        assertThat(open.getResolutionCode()).isEqualTo("CORE_SNAPSHOT_VALIDATED");
    }

    @Test
    void financialMismatchCreatesIncidentInsteadOfSilentlyRefreshing() {
        LoanServicingSnapshot invalid = new LoanServicingSnapshot(88L, "LC-001", "ACTIVE",
                money("9000000"), money("0"), money("9000000"), money("1000000"), money("0"),
                money("1000000"), money("0"), money("0"), money("10000000"), money("0"),
                null, 0, LocalDate.of(2026, 11, 4), money("1000000"), LocalDate.of(2027, 10, 4));
        when(incidents.findOpenForUpdate(1L,
                ReconciliationIncidentType.PRINCIPAL_DISBURSED_MISMATCH)).thenReturn(Optional.empty());

        var result = service.inspect(loan, invalid, NOW);

        assertThat(result).containsExactly(ReconciliationIncidentType.PRINCIPAL_DISBURSED_MISMATCH);
        verify(incidents).save(any(LoanReconciliationIncident.class));
        verify(incidents, never()).findOpenByLoanIdForUpdate(any());
    }

    @Test
    void repeatedMismatchUpdatesSameOpenIncident() {
        LoanReconciliationIncident open = LoanReconciliationIncident.open(1L, "LN-001",
                ReconciliationIncidentType.EXTERNAL_ID_MISMATCH, "LC-001", "wrong-1", NOW.minusSeconds(60));
        when(incidents.findOpenForUpdate(1L,
                ReconciliationIncidentType.EXTERNAL_ID_MISMATCH)).thenReturn(Optional.of(open));
        LoanServicingSnapshot invalid = new LoanServicingSnapshot(88L, "wrong-2", "ACTIVE",
                money("10000000"), money("0"), money("10000000"), money("1000000"), money("0"),
                money("1000000"), money("0"), money("0"), money("11000000"), money("0"),
                null, 0, LocalDate.of(2026, 11, 4), money("1000000"), LocalDate.of(2027, 10, 4));

        service.inspect(loan, invalid, NOW);

        assertThat(open.getOccurrenceCount()).isEqualTo(2);
        assertThat(open.getActualValue()).isEqualTo("wrong-2");
        verify(incidents, never()).save(any());
    }

    private static LoanServicingSnapshot validSnapshot() {
        return new LoanServicingSnapshot(88L, "LC-001", "ACTIVE", money("10000000"), money("0"),
                money("10000000"), money("1000000"), money("0"), money("1000000"), money("0"),
                money("0"), money("11000000"), money("0"), null, 0,
                LocalDate.of(2026, 11, 4), money("1000000"), LocalDate.of(2027, 10, 4));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
