package com.finora.loan.messaging.event;

/** Payload v1 tối thiểu để Investment tạo market projection, không chứa PII/KYC. */
public record LoanFundingRequestedEventData(
        Long loanApplicationId,
        String applicationNumber,
        Integer listingVersion,
        Integer fundingRound,
        String productCode,
        String purpose,
        String borrowerRegion,
        String creditGrade,
        Integer creditScore,
        String targetAmount,
        String annualInterestRate,
        Integer termMonths,
        String repaymentMethod,
        String termsVersion,
        String termsHash
) {
}
