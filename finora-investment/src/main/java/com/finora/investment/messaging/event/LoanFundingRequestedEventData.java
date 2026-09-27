package com.finora.investment.messaging.event;

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
