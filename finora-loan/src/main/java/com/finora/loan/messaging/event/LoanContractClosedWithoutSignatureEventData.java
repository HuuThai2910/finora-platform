package com.finora.loan.messaging.event;

import com.finora.loan.domain.contract.LoanContractStatus;
import java.time.Instant;

public record LoanContractClosedWithoutSignatureEventData(
        String contractNumber,
        Long applicationId,
        LoanContractStatus status,
        String reasonCode,
        Instant occurredAt
) {
}
