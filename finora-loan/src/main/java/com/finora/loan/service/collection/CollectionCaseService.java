package com.finora.loan.service.collection;

import com.finora.loan.domain.collection.CollectionCaseStatus;
import com.finora.loan.domain.collection.LoanCollectionCase;
import com.finora.loan.domain.servicing.FinoraLoan;
import com.finora.loan.domain.servicing.FinoraLoanStatus;
import com.finora.loan.domain.servicing.LoanServicingProjection;
import com.finora.loan.repository.collection.LoanCollectionCaseRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Đồng bộ current collection episode trong cùng transaction với servicing projection. */
@Service
@RequiredArgsConstructor
public class CollectionCaseService {
    private final LoanCollectionCaseRepository cases;

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(FinoraLoan loan, LoanServicingProjection projection, Instant observedAt) {
        LoanCollectionCase current = cases.findByLoanIdAndStatusForUpdate(
                loan.getId(), CollectionCaseStatus.OPEN).orElse(null);
        if (projection.getDaysPastDue() <= 0 || projection.getOverdueAmount().signum() <= 0) {
            if (current != null) current.close(projection.getTotalOutstanding().signum() == 0
                    ? CollectionCaseStatus.SETTLED : CollectionCaseStatus.CURED, observedAt);
            if (loan.getStatus() == FinoraLoanStatus.DEFAULTED && projection.getTotalOutstanding().signum() > 0) {
                loan.cureDefault(observedAt);
            }
            return;
        }
        if (current == null) {
            current = cases.save(LoanCollectionCase.open(loan.getId(), loan.getLoanNumber(), loan.getBorrowerId(),
                    projection.getDaysPastDue(), projection.getOverdueAmount(), projection.getTotalOutstanding(),
                    projection.getOverdueSince(), observedAt));
        } else {
            current.observe(projection.getDaysPastDue(), projection.getOverdueAmount(),
                    projection.getTotalOutstanding(), projection.getOverdueSince(), observedAt);
        }
        if (projection.getDaysPastDue() >= 91) loan.markDefaulted(observedAt);
    }
}
