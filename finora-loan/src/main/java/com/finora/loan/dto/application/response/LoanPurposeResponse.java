package com.finora.loan.dto.application.response;

public record LoanPurposeResponse(
        String code,
        String label,
        String aiValue,
        boolean requiresDetail
) {
}
