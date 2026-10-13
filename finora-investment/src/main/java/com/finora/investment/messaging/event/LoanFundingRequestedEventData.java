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
        String termsHash,
        /**
         * ID logic người vay (claim {@code user_id}), không phải PII. Investment dùng để chặn
         * người vay tự góp vốn khoản của mình. Trường bổ sung v1: producer cũ để null.
         */
        String borrowerId
) {
}
