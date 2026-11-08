package com.finora.loan.dto.restructure.response;

public record LoanReschedulePolicyResponse(
        String termsVersion,
        String termsText,
        String termsHash,
        int maximumExtraTermsPerRequest,
        int maximumCumulativeExtraTerms,
        int maximumTotalTerms
) {}

