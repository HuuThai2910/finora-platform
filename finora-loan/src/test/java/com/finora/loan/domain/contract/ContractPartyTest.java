package com.finora.loan.domain.contract;

import com.finora.loan.exception.LoanDomainException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractPartyTest {

    private static final Instant NOW = Instant.parse("2026-09-27T06:00:00Z");
    private static final String ACTOR = "INVESTOR-001";
    private static final String TRANSACTION = "FINORA-INV-TX-001";
    private static final String DOCUMENT = "FINORA-DOC-001";
    private static final String REQUEST_HASH = "a".repeat(64);
    private static final String EVIDENCE_HASH = "b".repeat(64);

    @Test
    void smartCaLifecyclePersistsPendingRequestAndCompletion() {
        ContractParty party = lender();

        assertThat(party.beginDigitalSignature(
                TRANSACTION, DOCUMENT, "INV-KEY-001", REQUEST_HASH, ACTOR, NOW)).isTrue();
        assertThat(party.getStatus()).isEqualTo(ContractPartyStatus.SIGNING);
        assertThat(party.getSignatureProvider()).isEqualTo(SignatureProviderType.VNPT_SMART_CA);
        assertThat(party.getSignatureMethod()).isEqualTo(SignatureMethod.VNPT_SMART_CA);
        assertThat(party.getSignatureDocumentId()).isEqualTo(DOCUMENT);
        assertThat(party.getSignatureRequestedAt()).isEqualTo(NOW);

        assertThat(party.beginDigitalSignature(
                TRANSACTION, DOCUMENT, "INV-KEY-001", REQUEST_HASH, ACTOR, NOW)).isFalse();
        assertThat(party.completeDigitalSignature(
                TRANSACTION, DOCUMENT, EVIDENCE_HASH, ACTOR, NOW.plusSeconds(20))).isTrue();
        assertThat(party.getStatus()).isEqualTo(ContractPartyStatus.SIGNED);
        assertThat(party.getSignatureEvidenceHash()).isEqualTo(EVIDENCE_HASH);
        assertThat(party.getSignedAt()).isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    void rejectedSmartCaRequestCanReturnToPendingForNewAttempt() {
        ContractParty party = lender();
        party.beginDigitalSignature(
                TRANSACTION, DOCUMENT, "INV-KEY-001", REQUEST_HASH, ACTOR, NOW);

        party.resetRejectedDigitalSignature(ACTOR, NOW.plusSeconds(10));

        assertThat(party.getStatus()).isEqualTo(ContractPartyStatus.PENDING_SIGNATURE);
        assertThat(party.getSignatureProvider()).isNull();
        assertThat(party.getSignatureTransactionId()).isNull();
        assertThat(party.getSignatureDocumentId()).isNull();
        assertThat(party.getIdempotencyKey()).isNull();
    }

    @Test
    void smartCaCompletionRejectsAnotherTransaction() {
        ContractParty party = lender();
        party.beginDigitalSignature(
                TRANSACTION, DOCUMENT, "INV-KEY-001", REQUEST_HASH, ACTOR, NOW);

        assertThatThrownBy(() -> party.completeDigitalSignature(
                "FINORA-INV-TX-OTHER", DOCUMENT, EVIDENCE_HASH, ACTOR, NOW.plusSeconds(20)))
                .isInstanceOf(LoanDomainException.class)
                .hasMessageContaining("không khớp");
    }

    private ContractParty lender() {
        return ContractParty.lender(
                10L, 101L, ACTOR, new BigDecimal("1000000.00"),
                new BigDecimal("10.000000"), NOW.minusSeconds(60));
    }
}
