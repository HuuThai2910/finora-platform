package com.finora.loan.service.disbursement;

import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.ContractParty;
import com.finora.loan.domain.contract.ContractPartyType;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.disbursement.DisbursementSaga;
import com.finora.loan.domain.disbursement.DisbursementSagaStatus;
import com.finora.loan.domain.messaging.ProcessedEvent;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.fineract.client.CoreLoanBookingGateway;
import com.finora.loan.integration.fineract.client.FineractIntegrationException;
import com.finora.loan.messaging.event.*;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.disbursement.DisbursementSagaRepository;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.service.outbox.OutboxService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class DisbursementSagaService {
    private final DisbursementSagaRepository sagaRepository;
    private final LoanApplicationRepository applicationRepository;
    private final LoanContractRepository contractRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxService outboxService;
    private final CoreLoanBookingGateway coreGateway;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public void start(LoanContract contract, LoanApplication application, List<ContractParty> parties, Instant now) {
        if (sagaRepository.findByLoanApplicationId(application.getId()).isPresent()) return;
        DisbursementSaga saga = sagaRepository.save(DisbursementSaga.waitingPayment(
                application.getId(), contract.getContractNumber(), application.getInvestmentListingId(),
                application.getBorrowerId(), contract.getPrincipalAmount(), now));
        List<DisbursementAllocationEventData> allocations = parties.stream()
                .filter(party -> party.getPartyType() == ContractPartyType.LENDER)
                .map(party -> new DisbursementAllocationEventData(
                        party.getCommitmentId(), party.getPartyId(), party.getAllocationAmount().toPlainString(),
                        party.getPaymentHoldReference()))
                .toList();
        outboxService.recordForPublication("DisbursementSaga", saga.getSagaId().toString(),
                "DisbursementRequested", 2,
                new DisbursementRequestedEventData(saga.getSagaId(), application.getId(),
                        application.getApplicationNumber(), contract.getContractNumber(),
                        application.getInvestmentListingId(), application.getBorrowerId(),
                        contract.getPrincipalAmount().toPlainString(), "VND", allocations));
    }

    @Transactional
    public void handleCompleted(UUID eventId, Instant occurredAt, DisbursementCompletedEventData data) {
        if (processedEventRepository.existsByEventId(eventId)) return;
        DisbursementSaga saga = locked(data.sagaId());
        requireIdentity(saga, data.loanApplicationId(), data.contractNumber(), data.listingId());
        saga.paymentCompleted(data.paymentReference(), clock.instant());
        processedEventRepository.save(ProcessedEvent.create(eventId, "DisbursementCompleted", 1,
                "finora-payment", data.sagaId().toString(), occurredAt, clock.instant()));
    }

    @Transactional
    public void handleFailed(UUID eventId, Instant occurredAt, DisbursementFailedEventData data) {
        if (processedEventRepository.existsByEventId(eventId)) return;
        DisbursementSaga saga = locked(data.sagaId());
        requireIdentity(saga, data.loanApplicationId(), data.contractNumber(), saga.getListingId());
        saga.paymentFailed(data.errorCode(), data.errorMessage(), clock.instant());
        processedEventRepository.save(ProcessedEvent.create(eventId, "DisbursementFailed", 1,
                "finora-payment", data.sagaId().toString(), occurredAt, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        return sagaRepository.findDueIds(List.of(DisbursementSagaStatus.CORE_BOOKING_PENDING,
                DisbursementSagaStatus.RETRY_PENDING), clock.instant(), PageRequest.of(0, 20));
    }

    @Transactional
    public CoreBookingWork claim(Long id) {
        DisbursementSaga saga = sagaRepository.findByIdForUpdate(id).orElseThrow();
        if (saga.getStatus() != DisbursementSagaStatus.CORE_BOOKING_PENDING
                && saga.getStatus() != DisbursementSagaStatus.RETRY_PENDING) return null;
        saga.startCoreBooking(clock.instant());
        LoanApplication application = applicationRepository.findById(saga.getLoanApplicationId()).orElseThrow();
        LoanContract contract = contractRepository.findByContractNumber(saga.getContractNumber()).orElseThrow();
        return new CoreBookingWork(saga.getSagaId(), new CoreLoanBookingGateway.CoreLoanBookingCommand(
                application.getId(), contract.getContractNumber(), application.getBorrowerId(),
                application.getFineractProductIdSnapshot(), contract.getPrincipalAmount(), contract.getTermMonths(),
                contract.getAnnualInterestRate(), contract.getExpectedDisbursementDate(), saga.getPaymentReference()));
    }

    public void execute(Long id) {
        CoreBookingWork work = transactionTemplate.execute(status -> claim(id));
        if (work == null) return;
        try {
            CoreLoanBookingGateway.CoreLoanBookingResult result = coreGateway.bookAndDisburse(work.command());
            transactionTemplate.executeWithoutResult(status -> complete(work.sagaId(), result.fineractLoanId()));
        } catch (FineractIntegrationException exception) {
            transactionTemplate.executeWithoutResult(status ->
                    fail(work.sagaId(), exception.getCode(), exception.getMessage(), exception.isRetryable()));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status ->
                    fail(work.sagaId(), "CORE_BOOKING_UNEXPECTED", "Không ghi nhận được khoản vay vào core", false));
        }
    }

    @Transactional
    public void complete(UUID sagaId, Long fineractLoanId) {
        DisbursementSaga saga = locked(sagaId);
        Instant now = clock.instant();
        saga.complete(fineractLoanId, now, now);
        LoanApplication application = applicationRepository.findById(saga.getLoanApplicationId()).orElseThrow();
        outboxService.recordForPublication("DisbursementSaga", sagaId.toString(), "LoanDisbursed", 1,
                new LoanDisbursedEventData(sagaId, application.getId(), application.getApplicationNumber(),
                        saga.getContractNumber(), saga.getListingId(), saga.getAmount().toPlainString(),
                        saga.getCurrency(), saga.getPaymentReference(), fineractLoanId, now));
    }

    @Transactional
    public void fail(UUID sagaId, String code, String detail, boolean retryable) {
        DisbursementSaga saga = locked(sagaId);
        boolean canRetry = retryable && saga.getAttemptCount() < 3;
        saga.failCore(code, detail, canRetry, canRetry ? clock.instant().plus(Duration.ofSeconds(30)) : null,
                clock.instant());
    }

    private DisbursementSaga locked(UUID sagaId) {
        return sagaRepository.findBySagaIdForUpdate(sagaId).orElseThrow(() ->
                LoanBusinessException.conflict("DISBURSEMENT_SAGA_NOT_FOUND", "Không tìm thấy saga giải ngân"));
    }
    private void requireIdentity(DisbursementSaga saga, Long applicationId, String contractNumber, Long listingId) {
        if (!saga.getLoanApplicationId().equals(applicationId) || !saga.getContractNumber().equals(contractNumber)
                || !saga.getListingId().equals(listingId)) {
            throw LoanBusinessException.conflict("DISBURSEMENT_EVENT_MISMATCH", "Kết quả giải ngân không khớp saga");
        }
    }
    public record CoreBookingWork(UUID sagaId, CoreLoanBookingGateway.CoreLoanBookingCommand command) {}
}
