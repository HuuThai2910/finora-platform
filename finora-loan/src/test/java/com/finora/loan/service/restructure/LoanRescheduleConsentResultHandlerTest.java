package com.finora.loan.service.restructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.loan.domain.messaging.ProcessedEvent;
import com.finora.loan.domain.restructure.LoanRescheduleRequest;
import com.finora.loan.domain.restructure.LoanRescheduleStatus;
import com.finora.loan.domain.restructure.LoanRescheduleType;
import com.finora.loan.domain.servicing.FinoraLoan;
import com.finora.loan.domain.servicing.FinoraLoanStatus;
import com.finora.loan.messaging.event.LoanRescheduleConsentCompletedEventData;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.repository.restructure.LoanRescheduleRequestRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LoanRescheduleConsentResultHandlerTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private ProcessedEventRepository processed;
    @Mock private LoanRescheduleRequestRepository requests;
    @Mock private FinoraLoanRepository loans;

    private LoanRescheduleConsentResultHandler handler;
    private LoanRescheduleRequest request;
    private FinoraLoan loan;

    @BeforeEach
    void setUp() {
        handler = new LoanRescheduleConsentResultHandler(
                processed, requests, loans, Clock.fixed(NOW, ZoneOffset.UTC));
        request = LoanRescheduleRequest.submit(
                REQUEST_ID, 10L, "LN-10", "borrower-1", LoanRescheduleType.TERM_EXTENSION,
                LocalDate.of(2026, 11, 1), null, 2, "Giảm thu nhập", "TERMS-V1",
                "a".repeat(64), "submit-key", "b".repeat(64), LocalDate.of(2027, 8, 1),
                NOW.minusSeconds(120));
        request.approveForInvestorConsent("admin-1", "Đủ điều kiện", "approve-key", NOW.minusSeconds(60));
        loan = FinoraLoan.activate("LN-10", 99L, "APP-10", "LC-10", "borrower-1",
                88L, new BigDecimal("10000000.00"), "VND", NOW.minusSeconds(86400));
        ReflectionTestUtils.setField(loan, "id", 10L);
        when(requests.findByRequestIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(request));
        when(loans.findByIdForUpdate(10L)).thenReturn(Optional.of(loan));
    }

    @Test
    void allInvestorsApprovedOpensFineractWorkerGate() {
        handler.handle(UUID.randomUUID(), NOW,
                new LoanRescheduleConsentCompletedEventData(REQUEST_ID, 99L, true, 2, 2, NOW));

        assertThat(request.getStatus()).isEqualTo(LoanRescheduleStatus.CREATE_PENDING);
        assertThat(loan.getStatus()).isEqualTo(FinoraLoanStatus.RESTRUCTURING);
        verify(processed).save(any(ProcessedEvent.class));
    }

    @Test
    void oneInvestorRejectedKeepsCurrentLoanScheduleActive() {
        handler.handle(UUID.randomUUID(), NOW,
                new LoanRescheduleConsentCompletedEventData(REQUEST_ID, 99L, false, 2, 1, NOW));

        assertThat(request.getStatus()).isEqualTo(LoanRescheduleStatus.INVESTOR_REJECTED);
        assertThat(loan.getStatus()).isEqualTo(FinoraLoanStatus.ACTIVE);
        verify(processed).save(any(ProcessedEvent.class));
    }
}
