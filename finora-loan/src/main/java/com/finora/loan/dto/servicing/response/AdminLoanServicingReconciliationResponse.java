package com.finora.loan.dto.servicing.response;

import com.finora.loan.domain.servicing.FinoraLoanStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record AdminLoanServicingReconciliationResponse(
        String loanNumber,
        String applicationNumber,
        Long fineractLoanId,
        FinoraLoanStatus loanStatus,
        String fineractStatusCode,
        BigDecimal totalOutstanding,
        BigDecimal overdueAmount,
        int daysPastDue,
        String source,
        Instant dataAsOf,
        Instant lastSyncedAt,
        boolean stale
) {
}
