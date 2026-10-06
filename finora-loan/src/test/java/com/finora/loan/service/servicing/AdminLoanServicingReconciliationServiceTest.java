package com.finora.loan.service.servicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.loan.domain.servicing.FinoraLoan;
import com.finora.loan.domain.servicing.LoanServicingProjection;
import com.finora.loan.domain.servicing.LoanServicingSnapshot;
import com.finora.loan.domain.servicing.LoanReconciliationIncident;
import com.finora.loan.domain.servicing.ReconciliationIncidentStatus;
import com.finora.loan.domain.servicing.ReconciliationIncidentType;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.repository.servicing.LoanReconciliationIncidentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminLoanServicingReconciliationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @Mock private FinoraLoanRepository loans;
    @Mock private LoanServicingProjectionRepository projections;
    @Mock private LoanReconciliationIncidentRepository incidents;
    @Mock private LoanServicingSyncService syncService;
    private AdminLoanServicingReconciliationService service;
    private FinoraLoan loan;

    @BeforeEach
    void setUp() {
        service = new AdminLoanServicingReconciliationService(loans, projections, incidents, syncService);
        loan = FinoraLoan.activate("LN-001", 9L, "APP-001", "LC-001", "borrower-1",
                88L, money("10000000"), "VND", NOW.minusSeconds(86400));
        ReflectionTestUtils.setField(loan, "id", 1L);
        Jwt jwt = Jwt.withTokenValue("admin-token").header("alg", "none").subject("admin-1")
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")), "admin-1"));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsStaleProjectionWithOneBatchLoanLookup() {
        LoanServicingProjection stale = projection();
        when(projections.findByStaleTrueOrderByUpdatedAtAscIdAsc(any()))
                .thenReturn(new PageImpl<>(List.of(stale)));
        when(loans.findAllById(List.of(1L))).thenReturn(List.of(loan));

        var response = service.listStale(0, 20);

        assertThat(response.data()).singleElement().satisfies(item -> {
            assertThat(item.loanNumber()).isEqualTo("LN-001");
            assertThat(item.fineractLoanId()).isEqualTo(88L);
            assertThat(item.stale()).isTrue();
        });
        verify(loans).findAllById(List.of(1L));
    }

    @Test
    void manuallyReconcilesByReadOnlyServicingSync() {
        LoanServicingProjection fresh = projection();
        fresh.applyCoreSnapshot(new LoanServicingSnapshot(88L, "LC-001", "loanStatusType.active", money("10000000"),
                money("1000000"), money("9000000"), money("1000000"), money("100000"),
                money("900000"), money("0"), money("0"), money("9900000"), money("0"),
                null, 0, LocalDate.of(2026, 11, 4), money("1000000"),
                LocalDate.of(2027, 10, 4)), NOW);
        when(loans.findByLoanNumber("LN-001")).thenReturn(Optional.of(loan));
        when(projections.findByFinoraLoanId(1L)).thenReturn(Optional.of(fresh));

        var response = service.reconcile("LN-001");

        verify(syncService).sync(1L);
        assertThat(response.stale()).isFalse();
        assertThat(response.totalOutstanding()).isEqualByComparingTo("9900000.00");
    }

    @Test
    void listsOpenIncidentsWithoutCallingFineract() {
        LoanReconciliationIncident incident = LoanReconciliationIncident.open(1L, "LN-001",
                ReconciliationIncidentType.EXTERNAL_ID_MISMATCH, "LC-001", "wrong", NOW);
        when(incidents.findByStatusOrderByUpdatedAtAscIdAsc(
                org.mockito.ArgumentMatchers.eq(ReconciliationIncidentStatus.OPEN), any()))
                .thenReturn(new PageImpl<>(List.of(incident)));

        var response = service.listIncidents(null, null, 0, 20);

        assertThat(response.data()).singleElement().satisfies(item -> {
            assertThat(item.loanNumber()).isEqualTo("LN-001");
            assertThat(item.type()).isEqualTo(ReconciliationIncidentType.EXTERNAL_ID_MISMATCH);
            assertThat(item.occurrenceCount()).isEqualTo(1);
        });
        verify(syncService, org.mockito.Mockito.never()).sync(any());
    }

    private LoanServicingProjection projection() {
        return LoanServicingProjection.fromContractSnapshot(
                1L, money("10000000"), money("1000000"), money("0"), money("0"),
                money("11000000"), LocalDate.of(2026, 11, 4), money("1000000"),
                LocalDate.of(2027, 10, 4), NOW.minusSeconds(3600));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
