package com.finora.blockchain.integration.proof;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Provider local xác định; receipt luôn mang tiền tố MOCK để không bị hiểu nhầm là giao dịch Fabric thật. */
public final class MockProofLedger implements ProofLedger {

    @Override
    public ProofLedgerReceipt submit(ProofLedgerCommand command) {
        UUID transactionId = UUID.nameUUIDFromBytes(
                (command.proofId() + ":" + command.payloadHash()).getBytes(StandardCharsets.UTF_8));
        return new ProofLedgerReceipt("MOCK-" + transactionId, "MOCK-BLOCK-NOT-ON-CHAIN");
    }
}
