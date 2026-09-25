package com.finora.blockchain.service.proof;

import com.finora.blockchain.integration.proof.ProofLedgerCommand;
import java.util.UUID;

public record ClaimedProofSubmission(Long databaseId, UUID claimToken, ProofLedgerCommand command) {
}
