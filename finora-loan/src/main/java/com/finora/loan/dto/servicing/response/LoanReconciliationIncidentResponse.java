package com.finora.loan.dto.servicing.response;

import com.finora.loan.domain.servicing.ReconciliationIncidentStatus;
import com.finora.loan.domain.servicing.ReconciliationIncidentType;
import java.time.Instant;
import java.util.UUID;

public record LoanReconciliationIncidentResponse(
        UUID incidentId,
        String loanNumber,
        ReconciliationIncidentType type,
        ReconciliationIncidentStatus status,
        String expectedValue,
        String actualValue,
        int occurrenceCount,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        Instant resolvedAt,
        String resolutionCode,
        String resolvedBy
) {}
