package com.finora.loan.service.servicing;

import com.finora.loan.domain.servicing.DebtGroup;
import com.finora.loan.domain.servicing.FinoraLoan;
import com.finora.loan.domain.servicing.FinoraLoanStatus;
import com.finora.loan.domain.servicing.LoanServicingProjection;
import com.finora.loan.domain.servicing.LoanServicingSnapshot;
import com.finora.loan.integration.fineract.client.FineractServicingGateway;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.messaging.event.LoanDelinquencyChangedEventData;
import com.finora.loan.messaging.event.LoanSettledEventData;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.collection.CollectionCaseService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoanServicingSyncService {
    private final FinoraLoanRepository loans;
    private final LoanServicingProjectionRepository projections;
    private final FineractServicingGateway fineract;
    private final OutboxService outbox;
    private final CollectionCaseService collectionCases;
    private final LoanServicingReconciliationService reconciliation;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public List<Long> dueIds(int batchSize) {
        return transactionTemplate.execute(status -> loans.findIdsByStatusesOrderedByOldestProjection(
                List.of(FinoraLoanStatus.ACTIVE, FinoraLoanStatus.DEFAULTED),
                PageRequest.of(0, Math.max(1, Math.min(batchSize, 100)))));
    }

    public void sync(Long loanId) {
        Work work = transactionTemplate.execute(status -> loans.findById(loanId)
                .filter(loan -> loan.getStatus() == FinoraLoanStatus.ACTIVE
                        || loan.getStatus() == FinoraLoanStatus.DEFAULTED)
                .map(loan -> new Work(loan.getId(), loan.getFineractLoanId())).orElse(null));
        if (work == null) return;
        try {
            LoanServicingSnapshot snapshot = fineract.read(work.fineractLoanId(), LocalDate.now(clock));
            List<String> mismatches = transactionTemplate.execute(status -> apply(work.loanId(), snapshot));
            if (mismatches != null && !mismatches.isEmpty()) {
                throw new LoanBusinessException(HttpStatus.CONFLICT, "SERVICING_RECONCILIATION_REQUIRED",
                        "Snapshot Fineract vi phạm bất biến: " + String.join(",", mismatches));
            }
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> markStale(work.loanId()));
            throw exception;
        }
    }

    private List<String> apply(Long loanId, LoanServicingSnapshot snapshot) {
        FinoraLoan loan = loans.findByIdForUpdate(loanId).orElseThrow();
        if (loan.getStatus() != FinoraLoanStatus.ACTIVE && loan.getStatus() != FinoraLoanStatus.DEFAULTED) {
            return List.of();
        }
        LoanServicingProjection projection = projections.findByFinoraLoanIdForUpdate(loanId)
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu servicing projection"));
        int previousDpd = projection.getDaysPastDue();
        int previousGroup = DebtGroup.fromDaysPastDue(previousDpd);
        var now = clock.instant();
        List<String> mismatches = reconciliation.inspect(loan, snapshot, now).stream()
                .map(Enum::name).toList();
        if (!mismatches.isEmpty()) {
            projection.markStale(now);
            return mismatches;
        }
        projection.applyCoreSnapshot(snapshot, now);
        collectionCases.apply(loan, projection, now);
        if (snapshot.totalOutstanding().signum() == 0) {
            loan.settle(now);
            outbox.recordForPublication("FinoraLoan", loan.getId().toString(),
                    "LoanSettled", 1, new LoanSettledEventData(
                            loan.getLoanApplicationId(), loan.getLoanNumber(), loan.getContractNumber(),
                            loan.getBorrowerId(), loan.getFineractLoanId(), now));
        }
        int currentGroup = DebtGroup.fromDaysPastDue(projection.getDaysPastDue());
        if (previousDpd != projection.getDaysPastDue()) {
            outbox.recordForPublication("FinoraLoan", loan.getId().toString(),
                    "LoanDelinquencyChanged", 1, new LoanDelinquencyChangedEventData(
                            loan.getLoanApplicationId(), loan.getLoanNumber(), loan.getBorrowerId(),
                            loan.getFineractLoanId(), previousDpd, projection.getDaysPastDue(),
                            previousGroup, currentGroup, projection.getOverdueAmount().toPlainString(),
                            projection.getOverdueSince(), projection.getTotalOutstanding().toPlainString(), now));
        }
        return List.of();
    }

    private void markStale(Long loanId) {
        projections.findByFinoraLoanIdForUpdate(loanId).ifPresent(value -> value.markStale(clock.instant()));
    }

    private record Work(Long loanId, Long fineractLoanId) {}
}
