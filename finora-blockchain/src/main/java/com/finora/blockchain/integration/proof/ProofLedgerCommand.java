package com.finora.blockchain.integration.proof;

import com.finora.blockchain.domain.proof.ProofType;
import java.util.UUID;

/** Dữ liệu gửi ledger: chỉ định danh, hash và metadata tối thiểu (chaincode RegisterProof). */
public record ProofLedgerCommand(
        UUID proofId,
        String aggregateType,
        String aggregateId,
        ProofType proofType,
        String payloadHash,
        int payloadVersion,
        String sourceService
) {
}
