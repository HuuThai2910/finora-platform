package com.finora.blockchain.service.proof;

import com.finora.blockchain.domain.proof.ProofProviderType;
import com.finora.blockchain.domain.proof.ProofSubmission;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import java.time.Instant;
import java.util.UUID;

public record ProofSubmissionView(
        UUID proofId,
        ProofProviderType provider,
        ProofSubmissionStatus status,
        int attemptCount,
        String providerTransactionId,
        String providerBlockReference,
        Instant confirmedAt
) {
    public static ProofSubmissionView from(ProofSubmission proof) {
        return new ProofSubmissionView(
                proof.getProofId(), proof.getProvider(), proof.getStatus(), proof.getAttemptCount(),
                proof.getProviderTransactionId(), proof.getProviderBlockReference(), proof.getConfirmedAt());
    }
}
