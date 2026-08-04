package com.finora.loan.service;

public record CreditScoringInputSources(
        String borrowerProfileSource,
        String kycVersion,
        String financialInformationSource,
        String scheduleCalculationPolicyVersion,
        String internalCreditSource,
        String internalCreditPolicyVersion,
        String publicRecordAdapterPolicy
) {
}
