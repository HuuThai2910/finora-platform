package com.finora.loan.domain.contract;

public enum LoanContractStatus {
    PENDING_SIGNATURE,
    SIGNING,
    SIGNED,
    DECLINED,
    EXPIRED,
    EFFECTIVE,
    COMPLETED
}
