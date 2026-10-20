package com.finora.loan.domain.contract;

/** Loại artifact PDF bất biến; mỗi loại chỉ tồn tại một lần trên một Contract. */
public enum ContractPdfArtifactType {
    SIGNABLE,
    /** Bản PAdES đã đủ chữ ký các bên; các bản trung gian của chuỗi chữ ký nằm ở {@code loan_contract_signatures}. */
    SIGNED_PDF,
    SIGNED_RECEIPT
}
