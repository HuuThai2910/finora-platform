package com.finora.loan.service.disbursement;

import com.finora.loan.config.DisbursementSagaProperties;
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
import com.finora.loan.service.funding.LoanFundingCancellationService;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.servicing.ServicingActivationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisbursementSagaService {
    private final DisbursementSagaRepository sagaRepository;
    private final LoanApplicationRepository applicationRepository;
    private final LoanContractRepository contractRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxService outboxService;
    private final ServicingActivationService servicingActivationService;
    private final CoreLoanBookingGateway coreGateway;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final LoanFundingCancellationService fundingCancellationService;
    private final DisbursementSagaProperties properties;

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
        Instant now = clock.instant();
        // Chỉ khi saga còn chờ Payment (tiền chưa capture) mới hủy gọi vốn để trả tiền giữ cho nhà đầu tư. Event mâu
        // thuẫn với saga đã nhận tiền/đang ghi core bị domain từ chối và vào DLT, không tự hủy vốn.
        if (saga.paymentFailed(data.errorCode(), data.errorMessage(), now)) {
            fundingCancellationService.cancel(saga.getLoanApplicationId(), saga.getContractNumber(),
                    LoanFundingCancellationService.DISBURSEMENT_FAILED, now);
        }
        processedEventRepository.save(ProcessedEvent.create(eventId, "DisbursementFailed", 1,
                "finora-payment", data.sagaId().toString(), occurredAt, now));
    }

    /** Saga chờ ghi core, chờ retry, hoặc đang ghi core nhưng đã hết lease (worker trước có thể đã chết). */
    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        return sagaRepository.findDueIds(List.of(DisbursementSagaStatus.CORE_BOOKING_PENDING,
                DisbursementSagaStatus.RETRY_PENDING, DisbursementSagaStatus.CORE_BOOKING),
                clock.instant(), PageRequest.of(0, 20));
    }

    /**
     * Khóa saga và nhận một lần ghi core. Row lock bảo đảm hai worker cùng thấy lease hết hạn chỉ một bên nhận; bên còn
     * lại thấy lease mới và trả {@code null}. Lease hết hạn quá số lần cho phép thì dừng ở {@code REPAIR_REQUIRED}.
     */
    @Transactional
    public CoreBookingWork claim(Long id) {
        DisbursementSaga saga = sagaRepository.findByIdForUpdate(id).orElseThrow();
        Instant now = clock.instant();
        boolean leaseExpired = saga.isCoreBookingLeaseExpired(now);
        if (saga.getStatus() != DisbursementSagaStatus.CORE_BOOKING_PENDING
                && saga.getStatus() != DisbursementSagaStatus.RETRY_PENDING && !leaseExpired) return null;
        if (leaseExpired) {
            log.warn("Lease ghi core hết hạn, nhận lại saga: sagaId={}, attempt={}", saga.getSagaId(), saga.getAttemptCount());
            if (saga.attemptsSinceRepair() >= properties.maxAttempts()) {
                saga.markCoreBookingStalled(now);
                return null;
            }
        }
        saga.startCoreBooking(now, now.plus(properties.coreBookingLease()));
        LoanApplication application = applicationRepository.findById(saga.getLoanApplicationId()).orElseThrow();
        LoanContract contract = contractRepository.findByContractNumber(saga.getContractNumber()).orElseThrow();
        return new CoreBookingWork(saga.getSagaId(), saga.getAttemptCount(), new CoreLoanBookingGateway.CoreLoanBookingCommand(
                application.getId(), contract.getContractNumber(), application.getBorrowerId(),
                application.getFineractProductIdSnapshot(), contract.getPrincipalAmount(), contract.getTermMonths(),
                contract.getAnnualInterestRate(), application.getCoreConfigVersionSnapshot(),
                contract.getExpectedDisbursementDate(), saga.getPaymentReference()));
    }

    public void execute(Long id) {
        CoreBookingWork work = transactionTemplate.execute(status -> claim(id));
        if (work == null) return;
        try {
            CoreLoanBookingGateway.CoreLoanBookingResult result = coreGateway.bookAndDisburse(work.command());
            transactionTemplate.executeWithoutResult(status ->
                    complete(work.sagaId(), work.attempt(), result.fineractLoanId()));
        } catch (FineractIntegrationException exception) {
            transactionTemplate.executeWithoutResult(status -> fail(work.sagaId(), work.attempt(),
                    exception.getCode(), exception.getMessage(), exception.isRetryable()));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> fail(work.sagaId(), work.attempt(),
                    "CORE_BOOKING_UNEXPECTED", "Không ghi nhận được khoản vay vào core", false));
        }
    }

    @Transactional
    public void complete(UUID sagaId, int attempt, Long fineractLoanId) {
        DisbursementSaga saga = locked(sagaId);
        Instant now = clock.instant();
        if (!saga.complete(attempt, fineractLoanId, now, now)) {
            // Lần thử cũ trả kết quả sau khi lease đã bị nhận lại: lần thử mới sẽ reconcile cùng khoản vay theo externalId.
            log.warn("Bỏ qua kết quả core của lần thử cũ: sagaId={}, attempt={}, current={}, status={}",
                    sagaId, attempt, saga.getAttemptCount(), saga.getStatus());
            return;
        }
        LoanApplication application = applicationRepository.findById(saga.getLoanApplicationId()).orElseThrow();
        LoanContract contract = contractRepository.findByContractNumber(saga.getContractNumber()).orElseThrow();
        servicingActivationService.activate(application, contract, fineractLoanId, now);
        outboxService.recordForPublication("DisbursementSaga", sagaId.toString(), "LoanDisbursed", 1,
                new LoanDisbursedEventData(sagaId, application.getId(), application.getApplicationNumber(),
                        saga.getContractNumber(), saga.getListingId(), saga.getAmount().toPlainString(),
                        saga.getCurrency(), saga.getPaymentReference(), fineractLoanId,
                        application.getCoreConfigVersionSnapshot(), now, contract.getEarlySettlementPolicyVersion()));
    }

    @Transactional
    public void fail(UUID sagaId, int attempt, String code, String detail, boolean retryable) {
        DisbursementSaga saga = locked(sagaId);
        Instant now = clock.instant();
        boolean canRetry = retryable && saga.attemptsSinceRepair() < properties.maxAttempts();
        if (!saga.failCore(attempt, code, detail, canRetry, canRetry ? now.plus(properties.retryBackoff()) : null, now)) {
            log.warn("Bỏ qua lỗi core của lần thử cũ: sagaId={}, attempt={}, current={}, status={}",
                    sagaId, attempt, saga.getAttemptCount(), saga.getStatus());
        }
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
    /** {@code attempt} là claim token: kết quả chỉ được ghi khi saga vẫn ở đúng lần thử này. */
    public record CoreBookingWork(UUID sagaId, int attempt, CoreLoanBookingGateway.CoreLoanBookingCommand command) {}
}
