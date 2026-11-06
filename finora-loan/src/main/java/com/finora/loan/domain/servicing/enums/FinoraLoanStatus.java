package com.finora.loan.domain.servicing.enums;

/** Vòng đời khoản vay FINORA sau giải ngân; không dùng trạng thái hồ sơ vay để thay thế. */
public enum FinoraLoanStatus {
    ACTIVE,
    RESTRUCTURING,
    SETTLED,
    DEFAULTED,
    WRITTEN_OFF
}

