package com.finora.loan.mapper.scoring;

/** Nguồn và policy version của từng nhóm dữ liệu đã dùng để tạo request AI. */
public record CreditScoringSourceSnapshot(
        String borrowerProfileSource,
        String kycVersion,
        String eligibilityPolicyVersion,
        String financialInformationSource,
        String scheduleCalculationPolicyVersion,
        String internalCreditSource,
        String internalCreditPolicyVersion,
        String publicRecordAdapterPolicy
) {
}
