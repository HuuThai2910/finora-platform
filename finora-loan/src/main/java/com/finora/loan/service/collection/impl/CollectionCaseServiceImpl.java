package com.finora.loan.service.collection.impl;

import com.finora.loan.domain.collection.entity.LoanCollectionCase;
import com.finora.loan.domain.collection.enums.CollectionCaseStatus;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.entity.LoanServicingProjection;
import com.finora.loan.domain.servicing.enums.FinoraLoanStatus;
import com.finora.loan.domain.servicing.model.DebtGroup;
import com.finora.loan.repository.collection.LoanCollectionCaseRepository;
import com.finora.loan.service.collection.CollectionCaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Đồng bộ current collection episode trong cùng transaction với servicing projection.
 *
 * <p>Mở hồ sơ thu hồi khi khoản vay quá hạn, cập nhật khi còn quá hạn và đóng (CURED/SETTLED) khi hết quá hạn; DPD từ
 * 91 ngày chuyển khoản vay sang DEFAULTED.</p>
 */
@Service
@RequiredArgsConstructor
public class CollectionCaseServiceImpl implements CollectionCaseService {
    private final LoanCollectionCaseRepository loanCollectionCaseRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DebtGroupChange apply(FinoraLoan loan, LoanServicingProjection projection, int previousDpd, Instant observedAt) {
        LoanCollectionCase current = loanCollectionCaseRepository.findByLoanIdAndStatusForUpdate(
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
            current = loanCollectionCaseRepository.save(LoanCollectionCase.open(
                    loan.getId(), loan.getLoanNumber(), loan.getBorrowerId(),
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
