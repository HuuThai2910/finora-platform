package com.finora.loan.service.disbursement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.loan.domain.disbursement.DisbursementSaga;
import com.finora.loan.domain.disbursement.DisbursementSagaStatus;
import com.finora.loan.integration.fineract.client.CoreLoanBookingGateway;
import com.finora.loan.messaging.event.DisbursementFailedEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.disbursement.DisbursementSagaRepository;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.service.funding.LoanFundingCancellationService;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.servicing.ServicingActivationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class DisbursementSagaServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");

    private final DisbursementSagaRepository sagaRepository = mock(DisbursementSagaRepository.class);
    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final LoanFundingCancellationService fundingCancellation = mock(LoanFundingCancellationService.class);
    private final DisbursementSagaService service = new DisbursementSagaService(
            sagaRepository,
            mock(LoanApplicationRepository.class),
            mock(LoanContractRepository.class),
            processedEventRepository,
            mock(OutboxService.class),
            mock(ServicingActivationService.class),
            mock(CoreLoanBookingGateway.class),
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(TransactionTemplate.class),
            fundingCancellation);

    @Test
    void paymentFailureBeforeMoneyMovedCancelsFundingForInvestorRefund() {
        DisbursementSaga saga = waitingSaga();

        service.handleFailed(UUID.randomUUID(), NOW, failed(saga));

        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.PAYMENT_FAILED);
        verify(fundingCancellation).cancel(10L, "LC-E2E-1", LoanFundingCancellationService.DISBURSEMENT_FAILED, NOW);
    }

    @Test
    void contradictoryFailureAfterPaymentCompletedNeverReleasesInvestorMoney() {
        DisbursementSaga saga = waitingSaga();
        saga.paymentCompleted("MOCK-REFERENCE", NOW);

        service.handleFailed(UUID.randomUUID(), NOW, failed(saga));

        // Tiền đã capture vào ví người vay: hủy vốn lúc này sẽ trả tiền hai lần, phải để R2 đối soát.
        verify(fundingCancellation, never()).cancel(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void duplicateFailureEventIsIgnoredWithoutSecondCompensation() {
        DisbursementSaga saga = waitingSaga();
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByEventId(eventId)).thenReturn(true);

        service.handleFailed(eventId, NOW, failed(saga));

        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.WAITING_PAYMENT);
        verify(fundingCancellation, never()).cancel(anyLong(), anyString(), anyString(), any());
    }

    private DisbursementSaga waitingSaga() {
        DisbursementSaga saga = DisbursementSaga.waitingPayment(
                10L, "LC-E2E-1", 20L, "BORROWER-001", new BigDecimal("50000000.00"), NOW);
        when(sagaRepository.findBySagaIdForUpdate(saga.getSagaId())).thenReturn(Optional.of(saga));
        return saga;
    }

    private DisbursementFailedEventData failed(DisbursementSaga saga) {
        return new DisbursementFailedEventData(
                saga.getSagaId(), 10L, "LC-E2E-1", "MOCK_REJECTED", "Provider từ chối", NOW);
    }
}
