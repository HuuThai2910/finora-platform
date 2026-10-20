package com.finora.loan.service.collection;

import com.finora.loan.domain.collection.CollectionCaseStatus;
import com.finora.loan.domain.collection.LoanCollectionCase;
import com.finora.loan.domain.servicing.DebtGroup;
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

    /** Nhóm nợ trước và sau một lần quan sát theo D29: nhóm của hồ sơ thu hồi đang mở, không có hồ sơ thì theo DPD. */
    public record DebtGroupChange(int previous, int current) {}

    /**
     * Cập nhật hồ sơ thu hồi theo projection vừa đổi và trả nhóm nợ để event {@code LoanDelinquencyChanged} dùng đúng nhóm
     * của đợt quá hạn (D29), không tự tính lại từ DPD.
     *
     * @param previousDpd DPD của projection trước lần cập nhật này
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DebtGroupChange apply(FinoraLoan loan, LoanServicingProjection projection, int previousDpd, Instant observedAt) {
        LoanCollectionCase current = cases.findByLoanIdAndStatusForUpdate(
                loan.getId(), CollectionCaseStatus.OPEN).orElse(null);
        int previousGroup = current != null ? current.getDebtGroup() : DebtGroup.fromDaysPastDue(previousDpd);
        if (projection.getDaysPastDue() <= 0 || projection.getOverdueAmount().signum() <= 0) {
            if (current != null) current.close(projection.getTotalOutstanding().signum() == 0
                    ? CollectionCaseStatus.SETTLED : CollectionCaseStatus.CURED, observedAt);
            if (loan.getStatus() == FinoraLoanStatus.DEFAULTED && projection.getTotalOutstanding().signum() > 0) {
                loan.cureDefault(observedAt);
            }
            return new DebtGroupChange(previousGroup, DebtGroup.FIRST);
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
        return new DebtGroupChange(previousGroup, current.getDebtGroup());
    }
}
