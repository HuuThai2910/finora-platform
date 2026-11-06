package com.finora.blockchain.integration.fabric.client.impl;

import com.finora.blockchain.domain.proof.enums.ProofProviderType;
import com.finora.blockchain.domain.proof.model.ProofReceiptFormat;
import com.finora.blockchain.integration.fabric.ProofLedgerException;
import com.finora.blockchain.integration.fabric.client.ProofLedger;
import com.finora.blockchain.integration.fabric.contract.LedgerProofRecord;
import com.finora.blockchain.integration.fabric.contract.ProofLedgerCommand;
import com.finora.blockchain.integration.fabric.contract.ProofLedgerReceipt;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/** Provider local xác định; receipt luôn mang tiền tố MOCK để không bị hiểu nhầm là giao dịch Fabric thật. */
public class MockProofLedger implements ProofLedger {

    @Override
    public ProofProviderType provider() {
        return ProofProviderType.MOCK;
    }

    @Override
    public ProofLedgerReceipt submit(ProofLedgerCommand command) {
        UUID transactionId = UUID.nameUUIDFromBytes(
                (command.proofId() + ":" + command.payloadHash()).getBytes(StandardCharsets.UTF_8));
        return new ProofLedgerReceipt(
                ProofReceiptFormat.MOCK_TRANSACTION_PREFIX + transactionId, ProofReceiptFormat.MOCK_BLOCK_REFERENCE);
    }

    /** MOCK không có sổ cái để đối chiếu; caller phải kiểm provider trước và không được báo "không có trên chain". */
    @Override
    public Optional<LedgerProofRecord> read(UUID proofId) {
        throw new ProofLedgerException("MOCK_LEDGER_HAS_NO_CHAIN", "Ledger MOCK không có bản ghi trên chain để đọc", false);
    }
}
