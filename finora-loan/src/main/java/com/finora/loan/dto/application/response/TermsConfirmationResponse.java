package com.finora.loan.dto.application.response;

import com.finora.loan.domain.application.enums.TermsConfirmationStatus;

import java.time.Instant;

public record TermsConfirmationResponse(
        TermsConfirmationStatus status,
        String termsVersion,
        String termsHash,
        Instant expiresAt,
        Instant respondedAt,
        String declineReasonCode,
        String declineReasonDetail
) {
}
