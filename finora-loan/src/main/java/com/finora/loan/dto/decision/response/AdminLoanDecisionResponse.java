package com.finora.loan.dto.decision.response;

import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.domain.contract.LoanContractStatus;
import java.time.Instant;

public record AdminLoanDecisionResponse(
        String applicationNumber,
        LoanApplicationStatus applicationStatus,
        Long applicationVersion,
        String decisionReasonCode,
        String decisionPolicyVersion,
        String adminDecidedBy,
        Instant adminDecidedAt,
        String contractNumber,
        LoanContractStatus contractStatus,
        Long contractVersion,
        String documentHash,
        Instant expiresAt
) {
}
