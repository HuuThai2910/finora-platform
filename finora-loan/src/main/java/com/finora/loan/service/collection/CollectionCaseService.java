package com.finora.loan.service.collection;

import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.entity.LoanServicingProjection;

import java.time.Instant;

/**
 * Đồng bộ current collection episode trong cùng transaction với servicing projection.
 */
public interface CollectionCaseService {

    /**
     * Cập nhật hồ sơ thu hồi theo projection vừa đổi và trả nhóm nợ để event {@code LoanDelinquencyChanged} dùng đúng nhóm
     * của đợt quá hạn (D29), không tự tính lại từ DPD.
     *
     * @param previousDpd DPD của projection trước lần cập nhật này
     */
    DebtGroupChange apply(FinoraLoan loan, LoanServicingProjection projection, int previousDpd, Instant observedAt);

    /** Nhóm nợ trước và sau một lần quan sát theo D29: nhóm của hồ sơ thu hồi đang mở, không có hồ sơ thì theo DPD. */
    record DebtGroupChange(int previous, int current) {}
}
