package com.finora.blockchain.service.proof;

import com.finora.blockchain.domain.proof.enums.ProofProviderType;
import com.finora.blockchain.integration.fabric.contract.ProofLedgerCommand;

import java.util.UUID;

/** Proof đã được claim; {@code provider} là provider ghi trên bản ghi, receipt trả về phải thuộc đúng provider này. */
public record ClaimedProofSubmission(
        Long databaseId,
        UUID claimToken,
        ProofProviderType provider,
        ProofLedgerCommand command
) {
}
