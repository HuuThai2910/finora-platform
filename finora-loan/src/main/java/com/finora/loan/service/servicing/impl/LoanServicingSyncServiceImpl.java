package com.finora.loan.service.servicing.impl;

import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.entity.LoanServicingProjection;
import com.finora.loan.domain.servicing.enums.FinoraLoanStatus;
import com.finora.loan.domain.servicing.model.LoanServicingSnapshot;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.fineract.client.FineractServicingGateway;
import com.finora.loan.messaging.event.LoanDelinquencyChangedEventData;
import com.finora.loan.messaging.event.LoanSettledEventData;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.service.collection.CollectionCaseService;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.servicing.LoanServicingReconciliationService;
import com.finora.loan.service.servicing.LoanServicingSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Đồng bộ servicing projection của khoản vay từ Fineract, dùng chung cho worker và màn đối soát.
 *
 * <p>Đọc Fineract ngoài transaction; bước ghi khóa khoản vay và projection rồi cập nhật hồ sơ thu hồi, outbox trong
 * cùng transaction.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanServicingSyncServiceImpl implements LoanServicingSyncService {
    private final FinoraLoanRepository finoraLoanRepository;
    private final LoanServicingProjectionRepository loanServicingProjectionRepository;
    private final FineractServicingGateway fineractServicingGateway;
    private final OutboxService outboxService;
    private final CollectionCaseService collectionCaseService;
    private final LoanServicingReconciliationService loanServicingReconciliationService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @Override
    public List<Long> dueIds(int batchSize) {
        return transactionTemplate.execute(status -> finoraLoanRepository.findIdsByStatusesOrderedByOldestProjection(
                List.of(FinoraLoanStatus.ACTIVE, FinoraLoanStatus.DEFAULTED),
                PageRequest.of(0, Math.max(1, Math.min(batchSize, 100)))));
    }

    @Override
    public void sync(Long loanId) {
        Work work = transactionTemplate.execute(status -> finoraLoanRepository.findById(loanId)
                .filter(loan -> loan.getStatus() == FinoraLoanStatus.ACTIVE
                        || loan.getStatus() == FinoraLoanStatus.DEFAULTED)
                .map(loan -> new Work(loan.getId(), loan.getFineractLoanId())).orElse(null));
        if (work == null) return;
        try {
            LoanServicingSnapshot snapshot = fineractServicingGateway.read(work.fineractLoanId(), LocalDate.now(clock));
            List<String> mismatches = transactionTemplate.execute(status -> applySnapshot(work.loanId(), snapshot));
            if (mismatches != null && !mismatches.isEmpty()) {
                throw new LoanBusinessException(HttpStatus.CONFLICT, "SERVICING_RECONCILIATION_REQUIRED",
                        "Snapshot Fineract vi phạm bất biến: " + String.join(",", mismatches));
            }
        } catch (RuntimeException exception) {
            transactionTemplate.executeWithoutResult(status -> markStale(work.loanId()));
            throw exception;
        }
    }

    private List<String> applySnapshot(Long loanId, LoanServicingSnapshot snapshot) {
        FinoraLoan loan = finoraLoanRepository.findByIdForUpdate(loanId).orElseThrow();
        if (loan.getStatus() != FinoraLoanStatus.ACTIVE && loan.getStatus() != FinoraLoanStatus.DEFAULTED) {
            return List.of();
        }
        LoanServicingProjection projection = loanServicingProjectionRepository.findByFinoraLoanIdForUpdate(loanId)
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu servicing projection"));
        int previousDpd = projection.getDaysPastDue();
        var now = clock.instant();
        List<String> mismatches = loanServicingReconciliationService.inspect(loan, snapshot, now).stream()
                .map(Enum::name).toList();
        if (!mismatches.isEmpty()) {
            projection.markStale(now);
            return mismatches;
        }
        projection.applyCoreSnapshot(snapshot, now);
        CollectionCaseService.DebtGroupChange debtGroupChange =
                collectionCaseService.apply(loan, projection, previousDpd, now);
        if (snapshot.totalOutstanding().signum() == 0) {
            loan.settle(now);
            outboxService.recordForPublication("FinoraLoan", loan.getId().toString(),
                    "LoanSettled", 1, new LoanSettledEventData(
                            loan.getLoanApplicationId(), loan.getLoanNumber(), loan.getContractNumber(),
                            loan.getBorrowerId(), loan.getFineractLoanId(), now));
        }
        if (previousDpd != projection.getDaysPastDue()) {
            outboxService.recordForPublication("FinoraLoan", loan.getId().toString(),
                    "LoanDelinquencyChanged", 1, new LoanDelinquencyChangedEventData(
                            loan.getLoanApplicationId(), loan.getLoanNumber(), loan.getBorrowerId(),
                            loan.getFineractLoanId(), previousDpd, projection.getDaysPastDue(),
                            debtGroupChange.previous(), debtGroupChange.current(),
                            projection.getOverdueAmount().toPlainString(),
                            projection.getOverdueSince(), projection.getTotalOutstanding().toPlainString(), now));
        }
        return List.of();
    }

    private void markStale(Long loanId) {
        loanServicingProjectionRepository.findByFinoraLoanIdForUpdate(loanId)
                .ifPresent(projection -> projection.markStale(clock.instant()));
    }

    private record Work(Long loanId, Long fineractLoanId) {}
}
