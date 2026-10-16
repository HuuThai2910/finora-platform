package com.finora.loan.service.disbursement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finora.loan.config.DisbursementSagaProperties;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.disbursement.DisbursementSaga;
import com.finora.loan.domain.disbursement.DisbursementSagaStatus;
import com.finora.loan.integration.fineract.client.CoreLoanBookingGateway;
import com.finora.loan.messaging.event.DisbursementFailedEventData;
import com.finora.loan.messaging.event.LoanDisbursedEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.disbursement.DisbursementSagaRepository;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.service.funding.LoanFundingCancellationService;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.servicing.ServicingActivationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class DisbursementSagaServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final DisbursementSagaRepository sagaRepository = mock(DisbursementSagaRepository.class);
    private final LoanApplicationRepository applicationRepository = mock(LoanApplicationRepository.class);
    private final LoanContractRepository contractRepository = mock(LoanContractRepository.class);
    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final ServicingActivationService activationService = mock(ServicingActivationService.class);
    private final LoanFundingCancellationService fundingCancellation = mock(LoanFundingCancellationService.class);
    private final DisbursementSagaProperties properties = new DisbursementSagaProperties(5_000, 3, Duration.ofSeconds(30), LEASE);

    private DisbursementSagaService serviceAt(Instant now) {
        return new DisbursementSagaService(
                sagaRepository,
                applicationRepository,
                contractRepository,
                processedEventRepository,
                outboxService,
                activationService,
                mock(CoreLoanBookingGateway.class),
                Clock.fixed(now, ZoneOffset.UTC),
                mock(TransactionTemplate.class),
                fundingCancellation,
                properties);
    }

    @Test
    void paymentFailureBeforeMoneyMovedCancelsFundingForInvestorRefund() {
        DisbursementSaga saga = waitingSaga();

        serviceAt(NOW).handleFailed(UUID.randomUUID(), NOW, failed(saga));

        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.PAYMENT_FAILED);
        verify(fundingCancellation).cancel(10L, "LC-E2E-1", LoanFundingCancellationService.DISBURSEMENT_FAILED, NOW);
    }

    @Test
    void contradictoryFailureAfterPaymentCompletedIsRejectedWithoutReleasingInvestorMoney() {
        DisbursementSaga saga = waitingSaga();
        saga.paymentCompleted("MOCK-REFERENCE", NOW);

        // Tiền đã capture vào ví người vay: event phải vào DLT để đối soát, không được hủy vốn hay đổi saga.
        assertThatThrownBy(() -> serviceAt(NOW).handleFailed(UUID.randomUUID(), NOW, failed(saga)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.CORE_BOOKING_PENDING);
        verify(fundingCancellation, never()).cancel(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void duplicateFailureEventIsIgnoredWithoutSecondCompensation() {
        DisbursementSaga saga = waitingSaga();
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByEventId(eventId)).thenReturn(true);

        serviceAt(NOW).handleFailed(eventId, NOW, failed(saga));

        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.WAITING_PAYMENT);
        verify(fundingCancellation, never()).cancel(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void coreBookingWithLiveLeaseIsNotClaimedTwice() {
        DisbursementSaga saga = coreBookingSaga(NOW);

        assertThat(serviceAt(NOW.plus(LEASE).minusSeconds(1)).claim(1L)).isNull();
        assertThat(saga.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void expiredCoreBookingLeaseIsReclaimedWithNewClaimToken() {
        DisbursementSaga saga = coreBookingSaga(NOW);
        stubApplicationAndContract();

        DisbursementSagaService.CoreBookingWork work = serviceAt(NOW.plus(LEASE)).claim(1L);

        assertThat(work).isNotNull();
        assertThat(work.attempt()).isEqualTo(2);
        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.CORE_BOOKING);
        assertThat(saga.getNextRetryAt()).isEqualTo(NOW.plus(LEASE).plus(LEASE));
    }

    @Test
    void expiredLeaseAfterMaxAttemptsStopsForRepair() {
        DisbursementSaga saga = coreBookingSaga(NOW);
        Instant second = NOW.plus(LEASE);
        saga.startCoreBooking(second, second.plus(LEASE));
        Instant third = second.plus(LEASE);
        saga.startCoreBooking(third, third.plus(LEASE));

        assertThat(serviceAt(third.plus(LEASE)).claim(1L)).isNull();

        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.REPAIR_REQUIRED);
        assertThat(saga.getLastErrorCode()).isEqualTo("CORE_BOOKING_STALLED");
    }

    @Test
    void lateResultOfStaleAttemptDoesNotActivateLoanOrPublishEvent() {
        DisbursementSaga saga = coreBookingSaga(NOW);
        saga.startCoreBooking(NOW.plus(LEASE), NOW.plus(LEASE).plus(LEASE));
        when(sagaRepository.findBySagaIdForUpdate(saga.getSagaId())).thenReturn(Optional.of(saga));

        serviceAt(NOW.plus(LEASE).plusSeconds(5)).complete(saga.getSagaId(), 1, 9001L);

        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.CORE_BOOKING);
        verifyNoInteractions(activationService, outboxService);
    }

    @Test
    void completedCoreBookingPublishesLoanDisbursedWithContractFeePolicy() {
        DisbursementSaga saga = coreBookingSaga(NOW);
        when(sagaRepository.findBySagaIdForUpdate(saga.getSagaId())).thenReturn(Optional.of(saga));
        stubApplicationAndContract();

        serviceAt(NOW.plusSeconds(5)).complete(saga.getSagaId(), 1, 9001L);

        // Payment tính phí trả trước theo biểu phí hợp đồng mang trong LoanDisbursed (G-POLICY-W2).
        org.mockito.ArgumentCaptor<Object> data = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(outboxService).recordForPublication(org.mockito.ArgumentMatchers.eq("DisbursementSaga"), anyString(),
                org.mockito.ArgumentMatchers.eq("LoanDisbursed"), org.mockito.ArgumentMatchers.eq(1), data.capture());
        LoanDisbursedEventData event = (LoanDisbursedEventData) data.getValue();
        assertThat(event.fineractLoanId()).isEqualTo(9001L);
        assertThat(event.earlySettlementPolicyVersion()).isEqualTo("AGRIBANK_REFERENCE_FINORA_2026_V2");
        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.COMPLETED);
    }

    @Test
    void retryableCoreFailureUsesConfiguredBackoffUntilMaxAttempts() {
        DisbursementSaga saga = coreBookingSaga(NOW);
        when(sagaRepository.findBySagaIdForUpdate(saga.getSagaId())).thenReturn(Optional.of(saga));

        serviceAt(NOW).fail(saga.getSagaId(), 1, "FINERACT_TIMEOUT", "timeout", true);

        assertThat(saga.getStatus()).isEqualTo(DisbursementSagaStatus.RETRY_PENDING);
        assertThat(saga.getNextRetryAt()).isEqualTo(NOW.plusSeconds(30));
    }

    private DisbursementSaga waitingSaga() {
        DisbursementSaga saga = DisbursementSaga.waitingPayment(
                10L, "LC-E2E-1", 20L, "BORROWER-001", new BigDecimal("50000000.00"), NOW);
        when(sagaRepository.findBySagaIdForUpdate(saga.getSagaId())).thenReturn(Optional.of(saga));
        return saga;
    }

    private DisbursementSaga coreBookingSaga(Instant startedAt) {
        DisbursementSaga saga = DisbursementSaga.waitingPayment(
                10L, "LC-E2E-1", 20L, "BORROWER-001", new BigDecimal("50000000.00"), startedAt);
        saga.paymentCompleted("MOCK-REFERENCE", startedAt);
        saga.startCoreBooking(startedAt, startedAt.plus(LEASE));
        when(sagaRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(saga));
        return saga;
    }

    private void stubApplicationAndContract() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getId()).thenReturn(10L);
        when(application.getBorrowerId()).thenReturn("BORROWER-001");
        when(applicationRepository.findById(10L)).thenReturn(Optional.of(application));
        LoanContract contract = mock(LoanContract.class);
        when(contract.getContractNumber()).thenReturn("LC-E2E-1");
        when(contract.getPrincipalAmount()).thenReturn(new BigDecimal("50000000.00"));
        when(contract.getTermMonths()).thenReturn(6);
        when(contract.getEarlySettlementPolicyVersion()).thenReturn("AGRIBANK_REFERENCE_FINORA_2026_V2");
        when(contractRepository.findByContractNumber("LC-E2E-1")).thenReturn(Optional.of(contract));
    }

    private DisbursementFailedEventData failed(DisbursementSaga saga) {
        return new DisbursementFailedEventData(
                saga.getSagaId(), 10L, "LC-E2E-1", "MOCK_REJECTED", "Provider từ chối", NOW);
    }
}
