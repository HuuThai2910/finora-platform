package com.finora.blockchain.integration.proof;

/** Port ghi bằng chứng; implementation không được nhận payload gốc hoặc PII. */
public interface ProofLedger {

    ProofLedgerReceipt submit(ProofLedgerCommand command);
}
