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
    private static final String FABRIC_TX = "b".repeat(64);

    @Test
    void registerRejectsRawOrInvalidHash() {
        assertThatIllegalArgumentException().isThrownBy(() -> ProofSubmission.register(
                UUID.randomUUID(), source(), subject("LC-001"), "not-a-sha256", 2, ProofProviderType.MOCK, NOW));
    }

    @Test
    void aggregateIdThatLooksLikeFreeTextIsRejectedSoNoPersonalDataReachesTheLedger() {
        assertThatIllegalArgumentException().isThrownBy(() -> ProofSubmission.register(
                UUID.randomUUID(), source(), subject("Nguyễn Văn A"), HASH, 2, ProofProviderType.MOCK, NOW));
    }

    @Test
    void unknownHashSchemeVersionIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> ProofSubmission.register(
                UUID.randomUUID(), source(), subject("LC-001"), HASH, 9, ProofProviderType.MOCK, NOW));
    }

    @Test
    void claimAndConfirmRequireTheCurrentLeaseToken() {
        ProofSubmission proof = newProof(ProofProviderType.MOCK);
        UUID token = UUID.randomUUID();

        assertThat(proof.claim(NOW, NOW.minusSeconds(120), token)).isTrue();
        assertThatIllegalStateException().isThrownBy(() -> proof.confirm(
                UUID.randomUUID(), "MOCK-tx", ProofReceiptFormat.MOCK_BLOCK_REFERENCE, NOW.plusSeconds(1)));

        proof.confirm(token, "MOCK-tx", ProofReceiptFormat.MOCK_BLOCK_REFERENCE, NOW.plusSeconds(1));

        assertThat(proof.getStatus()).isEqualTo(ProofSubmissionStatus.CONFIRMED);
        assertThat(proof.getAttemptCount()).isEqualTo(1);
        assertThat(proof.getProviderTransactionId()).isEqualTo("MOCK-tx");
        assertThat(proof.getProcessingToken()).isNull();
        assertThat(proof.isOnChain()).isFalse();
    }

    @Test
    void fabricProofCannotBeConfirmedWithMockReceiptAndViceVersa() {
        ProofSubmission fabric = newProof(ProofProviderType.HYPERLEDGER_FABRIC);
        UUID fabricToken = UUID.randomUUID();
        fabric.claim(NOW, NOW.minusSeconds(120), fabricToken);
        assertThatIllegalStateException().isThrownBy(() -> fabric.confirm(
                fabricToken, "MOCK-tx", ProofReceiptFormat.MOCK_BLOCK_REFERENCE, NOW));

        ProofSubmission mock = newProof(ProofProviderType.MOCK);
        UUID mockToken = UUID.randomUUID();
        mock.claim(NOW, NOW.minusSeconds(120), mockToken);
        assertThatIllegalStateException().isThrownBy(() -> mock.confirm(mockToken, FABRIC_TX, "finora-channel/7", NOW));

        fabric.confirm(fabricToken, FABRIC_TX, "finora-channel/7", NOW);
        assertThat(fabric.isOnChain()).isTrue();
        assertThat(fabric.getProviderBlockReference()).isEqualTo("finora-channel/7");
    }

    @Test
    void retryReturnsToPendingAndPermanentFailureMovesToDeadLetter() {
        ProofSubmission proof = newProof(ProofProviderType.MOCK);
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

    @Test
    void deadLetterProofCanBeRequeuedButKeepsTheLastErrorAsTrace() {
        ProofSubmission proof = newProof(ProofProviderType.HYPERLEDGER_FABRIC);
        UUID token = UUID.randomUUID();
        proof.claim(NOW, NOW.minusSeconds(120), token);
        proof.fail(token, "FABRIC_IDENTITY_REJECTED", "PERMISSION_DENIED", false, 10, NOW, NOW);

        proof.requeue(NOW.plusSeconds(60));

        assertThat(proof.getStatus()).isEqualTo(ProofSubmissionStatus.PENDING);
        assertThat(proof.getAttemptCount()).isZero();
        assertThat(proof.getAvailableAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(proof.getLastErrorCode()).isEqualTo("FABRIC_IDENTITY_REJECTED");
        assertThatIllegalStateException().isThrownBy(() -> proof.requeue(NOW));
    }

    private static ProofSubmission newProof(ProofProviderType provider) {
        return ProofSubmission.register(UUID.randomUUID(), source(), subject("LC-001"), HASH, 2, provider, NOW);
    }

    private static ProofSource source() {
        return new ProofSource("finora-loan", UUID.randomUUID(), "LoanContractActivated", 1, NOW);
    }

    private static ProofSubject subject(String aggregateId) {
        return new ProofSubject(ProofType.CONTRACT_DOCUMENT, "LoanContract", aggregateId, 123L);
    }
}
