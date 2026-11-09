package com.finora.loan.dto.decision.response;

import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.application.enums.TermsConfirmationStatus;
import com.finora.loan.domain.contract.enums.LoanContractStatus;

import java.time.Instant;

public record AdminLoanDecisionResponse(
        String applicationNumber,
        LoanApplicationStatus applicationStatus,
        Long applicationVersion,
        String decisionReasonCode,
        String decisionPolicyVersion,
        String adminDecidedBy,
        Instant adminDecidedAt,
        TermsConfirmationStatus termsConfirmationStatus,
        String termsVersion,
        Instant termsExpiresAt,
        String contractNumber,
        LoanContractStatus contractStatus,
        Long contractVersion,
        String documentHash,
        Instant expiresAt,
        /** Mã hiển thị {@code HS-yyyyMMdd-XXXX}; {@code applicationNumber} vẫn là khóa tra cứu API. */
        String applicationCode,
        /** Mã hiển thị {@code HĐ-yyyyMMdd-XXXX}; {@code null} khi chưa có Contract. */
        String contractCode
) {
}
