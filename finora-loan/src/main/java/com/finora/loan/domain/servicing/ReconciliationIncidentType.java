package com.finora.loan.domain.servicing;

/** Sai lệch bất biến phải được điều tra; thay đổi dư nợ hợp lệ không thuộc nhóm này. */
public enum ReconciliationIncidentType {
    CORE_LOAN_ID_MISMATCH,
    EXTERNAL_ID_MISMATCH,
    PRINCIPAL_DISBURSED_MISMATCH,
    OUTSTANDING_BREAKDOWN_MISMATCH
}
