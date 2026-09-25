package com.finora.blockchain.domain.proof;

/** Loại bằng chứng được phép ghi; payload gốc luôn ở service nguồn. */
public enum ProofType {
    CONTRACT_DOCUMENT,
    DISBURSEMENT,
    REPAYMENT
}
