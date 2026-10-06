package com.finora.loan.service.restructure;

import com.finora.loan.config.LoanRescheduleProperties;
import com.finora.loan.domain.restructure.*;
import com.finora.loan.domain.servicing.FinoraLoan;
import com.finora.loan.domain.servicing.LoanServicingProjection;
import com.finora.loan.domain.servicing.LoanServicingSnapshot;
import com.finora.loan.integration.fineract.client.FineractIntegrationException;
import com.finora.loan.integration.fineract.client.FineractRescheduleGateway;
import com.finora.loan.integration.fineract.client.FineractServicingGateway;
import com.finora.loan.messaging.event.LoanRescheduledEventData;
import com.finora.loan.repository.restructure.LoanRescheduleRequestRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.collection.CollectionCaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class LoanRescheduleExecutionService {
    private final LoanRescheduleRequestRepository requests;
    private final FinoraLoanRepository loans;
    private final LoanServicingProjectionRepository projections;
    private final FineractRescheduleGateway rescheduleGateway;
    private final FineractServicingGateway servicingGateway;
    private final LoanRescheduleProperties properties;
    private final OutboxService outbox;
    private final CollectionCaseService collectionCases;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public List<Long> dueIds() {
        Instant now = clock.instant();
        return transactionTemplate.execute(status -> requests.findDueIds(
                List.of(LoanRescheduleStatus.CREATE_PENDING, LoanRescheduleStatus.APPROVAL_PENDING,
                        LoanRescheduleStatus.RECONCILIATION_REQUIRED),
                List.of(LoanRescheduleStatus.CREATING, LoanRescheduleStatus.APPROVING),
                now, now.minus(properties.processingLease()), PageRequest.of(0, properties.batchSize())));
    }

    public void execute(Long id) {
        Work work = transactionTemplate.execute(status -> claim(id));
        if (work == null) return;
        try {
            if (work.step() == LoanRescheduleStep.CREATE) executeCreate(work);
            else executeApprove(work);
        } catch (FineractIntegrationException exception) {
            transactionTemplate.executeWithoutResult(status -> fail(work.requestId(), exception.getCode(),
                    exception.isRetryable()));
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> fail(work.requestId(),
                    "RESTRUCTURE_UNEXPECTED_ERROR", false));
        }
    }

    private Work claim(Long id) {
        LoanRescheduleRequest request = requests.findByIdForUpdate(id).orElse(null);
        Instant now = clock.instant();
        if (request == null || !request.due(now, properties.processingLease())) return null;
        LoanRescheduleStep step = request.claim(now, properties.processingLease());
        if (step == null) return null;
        FinoraLoan loan = loans.findById(request.getFinoraLoanId()).orElseThrow();
        return new Work(request.getRequestId(), loan.getId(), loan.getLoanApplicationId(), loan.getLoanNumber(),
                loan.getBorrowerId(), loan.getFineractLoanId(), step, request.getRequestType(),
                request.getRescheduleFromDate(), request.getAdjustedDueDate(), request.getExtraTerms(),
                request.getFineractRescheduleId());
    }

    /** Reconcile marker trước create để timeout hoặc restart không tạo hai yêu cầu ở Fineract. */
    private void executeCreate(Work work) {
        String marker = marker(work.requestId());
        FineractRescheduleGateway.CoreReschedule core = rescheduleGateway
                .findByLoanAndMarker(work.fineractLoanId(), marker)
                .orElseGet(() -> rescheduleGateway.create(new FineractRescheduleGateway.CreateRescheduleCommand(
                        work.fineractLoanId(), work.requestType(), work.rescheduleFromDate(), work.adjustedDueDate(),
                        work.extraTerms(), properties.requireFineractReasonId(), marker, LocalDate.now(clock))));
        transactionTemplate.executeWithoutResult(status -> created(work.requestId(), core.id()));
    }

    /** Nếu approve đã thành công trước timeout, chỉ đọc lại snapshot; không gửi command lần hai. */
    private void executeApprove(Work work) {
        FineractRescheduleGateway.CoreReschedule core = rescheduleGateway.read(work.coreRescheduleId());
        if (core.rejected()) {
            throw new FineractIntegrationException("FINERACT_RESCHEDULE_REJECTED",
                    "Fineract đã từ chối yêu cầu cơ cấu", false, null);
        }
        if (!core.approved()) {
            rescheduleGateway.approve(work.coreRescheduleId(), LocalDate.now(clock));
        }
        LoanServicingSnapshot snapshot = servicingGateway.read(work.fineractLoanId(), LocalDate.now(clock));
        transactionTemplate.executeWithoutResult(status -> complete(work.requestId(), snapshot));
    }

    private void created(java.util.UUID requestId, Long coreId) {
        LoanRescheduleRequest request = requests.findByRequestIdForUpdate(requestId).orElseThrow();
        if (request.getStatus() == LoanRescheduleStatus.CREATING) request.coreCreated(coreId, clock.instant());
    }

    private void complete(java.util.UUID requestId, LoanServicingSnapshot snapshot) {
        LoanRescheduleRequest request = requests.findByRequestIdForUpdate(requestId).orElseThrow();
        if (request.getStatus() != LoanRescheduleStatus.APPROVING) return;
        FinoraLoan loan = loans.findByIdForUpdate(request.getFinoraLoanId()).orElseThrow();
        LoanServicingProjection projection = projections.findByFinoraLoanIdForUpdate(loan.getId()).orElseThrow();
        Instant now = clock.instant();
        LocalDate previousMaturity = request.getOriginalMaturityDate();
        projection.applyCoreSnapshot(snapshot, now);
        loan.completeRestructuring(now);
        collectionCases.apply(loan, projection, now);
        request.complete(snapshot.maturityDate(), now);
        outbox.recordForPublication("FinoraLoan", loan.getId().toString(), "LoanRescheduled", 1,
                new LoanRescheduledEventData(loan.getLoanApplicationId(), loan.getLoanNumber(), loan.getBorrowerId(),
                        loan.getFineractLoanId(), request.getRequestId(), request.getRequestType(), previousMaturity,
                        snapshot.maturityDate(), request.getRescheduleFromDate(), request.getAdjustedDueDate(),
                        request.getExtraTerms(), now));
    }

    private void fail(java.util.UUID requestId, String code, boolean retryable) {
        LoanRescheduleRequest request = requests.findByRequestIdForUpdate(requestId).orElseThrow();
        if (request.getStatus() != LoanRescheduleStatus.CREATING
                && request.getStatus() != LoanRescheduleStatus.APPROVING) return;
        Instant now = clock.instant();
        Duration backoff = backoff(request.getAttemptCount());
        request.fail(code, retryable, properties.maxAttempts(), now.plus(backoff), now);
    }

    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 20);
        try {
            Duration value = properties.retryDelay().multipliedBy(multiplier);
            return value.compareTo(properties.maximumRetryDelay()) > 0 ? properties.maximumRetryDelay() : value;
        } catch (ArithmeticException exception) {
            return properties.maximumRetryDelay();
        }
    }

    private static String marker(java.util.UUID requestId) {
        return "[FINORA:" + requestId + "]";
    }

    private record Work(java.util.UUID requestId, Long loanId, Long loanApplicationId, String loanNumber,
            String borrowerId, Long fineractLoanId, LoanRescheduleStep step, LoanRescheduleType requestType,
            LocalDate rescheduleFromDate, LocalDate adjustedDueDate, Integer extraTerms, Long coreRescheduleId) {}
}
