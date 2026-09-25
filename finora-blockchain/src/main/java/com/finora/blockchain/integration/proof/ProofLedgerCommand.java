package com.finora.blockchain.integration.proof;

import com.finora.blockchain.domain.proof.ProofType;
import java.util.UUID;

public record ProofLedgerCommand(
        UUID proofId,
        String aggregateType,
        String aggregateId,
        ProofType proofType,
        String payloadHash,
        int payloadVersion
) {
}
