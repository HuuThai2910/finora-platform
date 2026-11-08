package com.finora.loan.service.disbursement.impl;

import com.finora.loan.config.DisbursementSagaProperties;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.entity.ContractParty;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.enums.ContractPartyType;
import com.finora.loan.domain.disbursement.entity.DisbursementSaga;
import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.domain.messaging.entity.ProcessedEvent;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.fineract.FineractIntegrationException;
import com.finora.loan.integration.fineract.client.CoreLoanBookingGateway;
import com.finora.loan.messaging.event.*;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.disbursement.DisbursementSagaRepository;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.service.disbursement.DisbursementSagaService;
import com.finora.loan.service.funding.LoanFundingCancellationService;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.servicing.ServicingActivationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Disbursement Saga: mở saga khi hợp đồng có hiệu lực, nhận kết quả chuyển tiền của Payment, ghi khoản vay vào core
 * và phát {@code LoanDisbursed}.
 *
 * <p>Lần ghi core được nhận bằng lease trên dòng saga ({@code attempt} là claim token); lời gọi Fineract nằm ngoài
 * transaction.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisbursementSagaServiceImpl implements DisbursementSagaService {
    private final DisbursementSagaRepository disbursementSagaRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanContractRepository loanContractRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxService outboxService;
    private final ServicingActivationService servicingActivationService;
    private final CoreLoanBookingGateway coreLoanBookingGateway;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final LoanFundingCancellationService loanFundingCancellationService;
    private final DisbursementSagaProperties disbursementSagaProperties;
    private final com.finora.loan.config.E2EPausePoints e2ePausePoints;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void start(LoanContract contract, LoanApplication application, List<ContractParty> parties, Instant now) {
        if (disbursementSagaRepository.findByLoanApplicationId(application.getId()).isPresent()) return;
        DisbursementSaga saga = disbursementSagaRepository.save(DisbursementSaga.waitingPayment(
                application.getId(), contract.getContractNumber(), application.getInvestmentListingId(),
                application.getBorrowerId(), contract.getPrincipalAmount(), now));
        // Phần vốn đã rời hợp đồng (nhà đầu tư rút trước khi ký) không còn tiền giữ để capture.
        List<DisbursementAllocationEventData> allocations = parties.stream()
                .filter(ContractParty::isActiveLender)
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

    @Override
    @Transactional
    public void handleCompleted(UUID eventId, Instant occurredAt, DisbursementCompletedEventData eventData) {
        if (processedEventRepository.existsByEventId(eventId)) return;
        DisbursementSaga saga = locked(eventData.sagaId());
        requireIdentity(saga, eventData.loanApplicationId(), eventData.contractNumber(), eventData.listingId());
        saga.paymentCompleted(eventData.paymentReference(), clock.instant());
        processedEventRepository.save(ProcessedEvent.create(eventId, "DisbursementCompleted", 1,
                "finora-payment", eventData.sagaId().toString(), occurredAt, clock.instant()));
    }

    @Override
    @Transactional
    public void handleFailed(UUID eventId, Instant occurredAt, DisbursementFailedEventData eventData) {
        if (processedEventRepository.existsByEventId(eventId)) return;
        DisbursementSaga saga = locked(eventData.sagaId());
        requireIdentity(saga, eventData.loanApplicationId(), eventData.contractNumber(), saga.getListingId());
        Instant now = clock.instant();
        // Chỉ khi saga còn chờ Payment (tiền chưa capture) mới hủy gọi vốn để trả tiền giữ cho nhà đầu tư. Event mâu
        // thuẫn với saga đã nhận tiền/đang ghi core bị domain từ chối và vào DLT, không tự hủy vốn.
        if (saga.paymentFailed(eventData.errorCode(), eventData.errorMessage(), now)) {
            loanFundingCancellationService.cancel(saga.getLoanApplicationId(), saga.getContractNumber(),
                    LoanFundingCancellationService.DISBURSEMENT_FAILED, now);
        }
        processedEventRepository.save(ProcessedEvent.create(eventId, "DisbursementFailed", 1,
                "finora-payment", eventData.sagaId().toString(), occurredAt, now));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        return disbursementSagaRepository.findDueIds(List.of(DisbursementSagaStatus.CORE_BOOKING_PENDING,
                DisbursementSagaStatus.RETRY_PENDING, DisbursementSagaStatus.CORE_BOOKING),
                clock.instant(), PageRequest.of(0, 20));
    }

    @Override
    @Transactional
    public CoreBookingWork claim(Long id) {
        DisbursementSaga saga = disbursementSagaRepository.findByIdForUpdate(id).orElseThrow();
        Instant now = clock.instant();
        boolean leaseExpired = saga.isCoreBookingLeaseExpired(now);
        if (saga.getStatus() != DisbursementSagaStatus.CORE_BOOKING_PENDING
                && saga.getStatus() != DisbursementSagaStatus.RETRY_PENDING && !leaseExpired) return null;
        if (leaseExpired) {
            log.warn("Lease ghi core hết hạn, nhận lại saga: sagaId={}, attempt={}", saga.getSagaId(), saga.getAttemptCount());
            if (saga.attemptsSinceRepair() >= disbursementSagaProperties.maxAttempts()) {
                saga.markCoreBookingStalled(now);
                return null;
            }
        }
        saga.startCoreBooking(now, now.plus(disbursementSagaProperties.coreBookingLease()));
        LoanApplication application = loanApplicationRepository.findById(saga.getLoanApplicationId()).orElseThrow();
        LoanContract contract = loanContractRepository.findByContractNumber(saga.getContractNumber()).orElseThrow();
        return new CoreBookingWork(saga.getSagaId(), saga.getAttemptCount(), new CoreLoanBookingGateway.CoreLoanBookingCommand(
                application.getId(), contract.getContractNumber(), application.getBorrowerId(),
                application.getFineractProductIdSnapshot(), contract.getPrincipalAmount(), contract.getTermMonths(),
                contract.getAnnualInterestRate(), application.getRepaymentMethodSnapshot(),
                application.getCoreConfigVersionSnapshot(),
                contract.getExpectedDisbursementDate(), saga.getPaymentReference()));
    }

    @Override
    public void execute(Long id) {
        CoreBookingWork work = transactionTemplate.execute(status -> claim(id));
        if (work == null) return;
        try {
            CoreLoanBookingGateway.CoreLoanBookingResult result =
                    coreLoanBookingGateway.bookAndDisburse(work.command());
            // Q13: drill E2E tắt Loan đúng lúc Fineract đã ghi khoản vay mà saga chưa lưu kết quả.
            e2ePausePoints.pause(com.finora.loan.config.E2EPausePoints.CORE_BOOKED_BEFORE_SAVE);
            transactionTemplate.executeWithoutResult(status -> complete(work.sagaId(), work.attempt(), result));
        } catch (FineractIntegrationException exception) {
            transactionTemplate.executeWithoutResult(status -> fail(work.sagaId(), work.attempt(),
                    exception.getCode(), exception.getMessage(), exception.isRetryable()));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> fail(work.sagaId(), work.attempt(),
                    "CORE_BOOKING_UNEXPECTED", "Không ghi nhận được khoản vay vào core", false));
        }
    }

    @Override
    @Transactional
    public void complete(UUID sagaId, int attempt, Long fineractLoanId) {
        complete(sagaId, attempt, new CoreLoanBookingGateway.CoreLoanBookingResult(fineractLoanId));
    }

    @Override
    @Transactional
    public void complete(UUID sagaId, int attempt, Long fineractLoanId, java.time.LocalDate maturityDate) {
        complete(sagaId, attempt, new CoreLoanBookingGateway.CoreLoanBookingResult(fineractLoanId, maturityDate));
    }

    @Override
    @Transactional
    public void complete(UUID sagaId, int attempt, CoreLoanBookingGateway.CoreLoanBookingResult bookingResult) {
        Long fineractLoanId = bookingResult.fineractLoanId();
        DisbursementSaga saga = locked(sagaId);
        Instant now = clock.instant();
        if (!saga.complete(attempt, fineractLoanId, now, now)) {
            // Lần thử cũ trả kết quả sau khi lease đã bị nhận lại: lần thử mới sẽ reconcile cùng khoản vay theo externalId.
            log.warn("Bỏ qua kết quả core của lần thử cũ: sagaId={}, attempt={}, current={}, status={}",
                    sagaId, attempt, saga.getAttemptCount(), saga.getStatus());
            return;
        }
        LoanApplication application = loanApplicationRepository.findById(saga.getLoanApplicationId()).orElseThrow();
        LoanContract contract = loanContractRepository.findByContractNumber(saga.getContractNumber()).orElseThrow();
        var loan = servicingActivationService.activate(application, contract, fineractLoanId, now);
        outboxService.recordForPublication("DisbursementSaga", sagaId.toString(), "LoanDisbursed", 1,
                new LoanDisbursedEventData(sagaId, application.getId(), application.getApplicationNumber(),
                        saga.getContractNumber(), saga.getListingId(), saga.getAmount().toPlainString(),
                        saga.getCurrency(), saga.getPaymentReference(), fineractLoanId,
                        application.getCoreConfigVersionSnapshot(), now, contract.getEarlySettlementPolicyVersion(),
                        loan.getLoanNumber(), bookingResult.maturityDate(), application.getBorrowerId(),
                        bookingResult.nextDueDate(),
                        bookingResult.nextDueAmount() == null ? null : bookingResult.nextDueAmount().toPlainString()));
    }

    @Override
    @Transactional
    public void fail(UUID sagaId, int attempt, String code, String detail, boolean retryable) {
        DisbursementSaga saga = locked(sagaId);
        Instant now = clock.instant();
        boolean canRetry = retryable && saga.attemptsSinceRepair() < disbursementSagaProperties.maxAttempts();
        if (!saga.failCore(attempt, code, detail, canRetry,
                canRetry ? now.plus(disbursementSagaProperties.retryBackoff()) : null, now)) {
            log.warn("Bỏ qua lỗi core của lần thử cũ: sagaId={}, attempt={}, current={}, status={}",
                    sagaId, attempt, saga.getAttemptCount(), saga.getStatus());
        }
    }

    private DisbursementSaga locked(UUID sagaId) {
        return disbursementSagaRepository.findBySagaIdForUpdate(sagaId).orElseThrow(() ->
                LoanBusinessException.conflict("DISBURSEMENT_SAGA_NOT_FOUND", "Không tìm thấy saga giải ngân"));
    }
    private void requireIdentity(DisbursementSaga saga, Long applicationId, String contractNumber, Long listingId) {
        if (!saga.getLoanApplicationId().equals(applicationId) || !saga.getContractNumber().equals(contractNumber)
                || !saga.getListingId().equals(listingId)) {
            throw LoanBusinessException.conflict("DISBURSEMENT_EVENT_MISMATCH", "Kết quả giải ngân không khớp saga");
        }
    }
}
