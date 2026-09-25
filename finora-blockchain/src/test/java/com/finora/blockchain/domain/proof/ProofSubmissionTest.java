package com.finora.blockchain.domain.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProofSubmissionTest {

    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-21T05:00:00Z");

    @Test
    void registerRejectsRawOrInvalidHash() {
        assertThatIllegalArgumentException().isThrownBy(() -> ProofSubmission.register(
                UUID.randomUUID(), "finora-loan", UUID.randomUUID(), "LOAN_CONTRACT", "LC-001",
                ProofType.CONTRACT_DOCUMENT, "not-a-sha256", 1, ProofProviderType.MOCK, NOW));
    }

    @Test
    void claimAndConfirmRequireTheCurrentLeaseToken() {
        ProofSubmission proof = newProof();
        UUID token = UUID.randomUUID();

        assertThat(proof.claim(NOW, NOW.minusSeconds(120), token)).isTrue();
        assertThatIllegalStateException().isThrownBy(() -> proof.confirm(
                UUID.randomUUID(), "MOCK-tx", "MOCK-block", NOW.plusSeconds(1)));

        proof.confirm(token, "MOCK-tx", "MOCK-block", NOW.plusSeconds(1));

        assertThat(proof.getStatus()).isEqualTo(ProofSubmissionStatus.CONFIRMED);
        assertThat(proof.getAttemptCount()).isEqualTo(1);
        assertThat(proof.getProviderTransactionId()).isEqualTo("MOCK-tx");
        assertThat(proof.getProcessingToken()).isNull();
    }

    @Test
    void retryReturnsToPendingAndPermanentFailureMovesToDeadLetter() {
        ProofSubmission proof = newProof();
        UUID firstToken = UUID.randomUUID();
        proof.claim(NOW, NOW.minusSeconds(120), firstToken);

        proof.fail(firstToken, "TEMPORARY", "timeout", true, 2, NOW.plusSeconds(5), NOW);
        assertThat(proof.getStatus()).isEqualTo(ProofSubmissionStatus.PENDING);
        assertThat(proof.getAvailableAt()).isEqualTo(NOW.plusSeconds(5));

        UUID secondToken = UUID.randomUUID();
        proof.claim(NOW.plusSeconds(5), NOW.minusSeconds(115), secondToken);
        proof.fail(secondToken, "TEMPORARY", "timeout", true, 2, NOW.plusSeconds(15), NOW.plusSeconds(5));

        assertThat(proof.getStatus()).isEqualTo(ProofSubmissionStatus.DEAD_LETTER);
        assertThat(proof.getAttemptCount()).isEqualTo(2);
    }

    private static ProofSubmission newProof() {
        return ProofSubmission.register(
                UUID.randomUUID(), "finora-loan", UUID.randomUUID(), "LOAN_CONTRACT", "LC-001",
                ProofType.CONTRACT_DOCUMENT, HASH, 1, ProofProviderType.MOCK, NOW);
    }
}
