package com.finora.loan.dto.response;

public record LoanPurposeResponse(
        String code,
        String label,
        String aiValue,
        boolean requiresDetail
) {
}
