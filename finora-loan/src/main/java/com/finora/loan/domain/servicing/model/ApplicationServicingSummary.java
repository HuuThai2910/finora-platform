package com.finora.loan.domain.servicing.model;

import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.domain.servicing.enums.ApplicationServicingFlag;
import com.finora.loan.domain.servicing.enums.FinoraLoanStatus;

import java.util.List;

/**
 * Tình trạng sau giải ngân của một hồ sơ: mã và trạng thái khoản vay ({@code null} khi chưa kích hoạt), trạng thái saga
 * giải ngân ({@code null} khi chưa vào giải ngân) và các việc vận hành đang mở.
 */
public record ApplicationServicingSummary(
        String loanNumber,
        FinoraLoanStatus loanStatus,
        DisbursementSagaStatus disbursementStatus,
        List<ApplicationServicingFlag> flags
) {
    /** Hồ sơ chưa vào giải ngân. */
    public static final ApplicationServicingSummary NONE = new ApplicationServicingSummary(null, null, null, List.of());

    public ApplicationServicingSummary {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }
}
