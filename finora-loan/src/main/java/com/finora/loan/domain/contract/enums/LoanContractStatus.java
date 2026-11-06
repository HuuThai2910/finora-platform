package com.finora.loan.domain.contract.enums;

public enum LoanContractStatus {
    PENDING_LENDER_SIGNATURES,
    PENDING_BORROWER_SIGNATURE,
    PENDING_SIGNATURE,
    SIGNING,
    SIGNED,
    DECLINED,
    EXPIRED,
    EFFECTIVE,
    COMPLETED
}
