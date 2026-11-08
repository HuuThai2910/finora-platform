package com.finora.loan.domain.contract.enums;

public enum LoanContractStatus {
    PENDING_LENDER_SIGNATURES,
    /**
     * Một nhà đầu tư chưa ký đã từ chối hoặc hết hạn ký: phần vốn của họ được nhả, khoản vay quay lại sàn gọi phần
     * còn thiếu. Bên đã ký giữ nguyên; đủ vốn lại thì văn bản được phát hành lại và quay về PENDING_LENDER_SIGNATURES.
     */
    PENDING_REFUNDING,
    PENDING_BORROWER_SIGNATURE,
    PENDING_SIGNATURE,
    SIGNING,
    SIGNED,
    DECLINED,
    EXPIRED,
    EFFECTIVE,
    COMPLETED
}
