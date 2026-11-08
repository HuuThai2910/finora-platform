package com.finora.loan.domain.contract.entity;

import com.finora.loan.domain.contract.enums.ContractPartyStatus;
import com.finora.loan.domain.contract.enums.SignatureMethod;
import com.finora.loan.domain.contract.enums.SignatureProviderType;
import com.finora.loan.exception.LoanDomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

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

    @Test
    void systemExpiresOnlyAnOpenSmartCaSessionSoTheInvestorCanSignAgain() {
        ContractParty party = lender();
        assertThat(party.expireDigitalSignatureSession(NOW)).isFalse();
        party.beginDigitalSignature(
                TRANSACTION, DOCUMENT, "INV-KEY-001", REQUEST_HASH, ACTOR, NOW);

        assertThat(party.expireDigitalSignatureSession(NOW.plusSeconds(700))).isTrue();

        assertThat(party.getStatus()).isEqualTo(ContractPartyStatus.PENDING_SIGNATURE);
        assertThat(party.getSignatureTransactionId()).isNull();
        assertThat(party.getIdempotencyKey()).isNull();
        // Phiên đã đóng: lần ký mới có transaction mới.
        assertThat(party.beginDigitalSignature(
                "FINORA-INV-TX-002", DOCUMENT, "INV-KEY-002", REQUEST_HASH, ACTOR, NOW.plusSeconds(800))).isTrue();
    }

    @Test
    void investorCanDeclineBeforeButNotAfterSignature() {
        ContractParty pending = lender();
        assertThat(pending.decline("DECLINE-1", REQUEST_HASH, ACTOR, NOW)).isTrue();
        assertThat(pending.decline("DECLINE-1", REQUEST_HASH, ACTOR, NOW.plusSeconds(1))).isFalse();
        assertThat(pending.getStatus()).isEqualTo(ContractPartyStatus.DECLINED);

        ContractParty signed = lender();
        signed.recordSignature(
                SignatureMethod.CLICK_WRAP_MVP, SignatureProviderType.MOCK, TRANSACTION,
                EVIDENCE_HASH, "SIGN-1", REQUEST_HASH, ACTOR, NOW);
        assertThatThrownBy(() -> signed.decline("DECLINE-2", REQUEST_HASH, ACTOR, NOW.plusSeconds(1)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("CONTRACT_PARTY_ALREADY_SIGNED");
    }

    @Test
    void unsignedLenderLeavesContractOnExpiryButSignedLenderStays() {
        ContractParty unsigned = lender();
        ContractParty signed = lender();
        signed.signMock(TRANSACTION, EVIDENCE_HASH, "INV-KEY-001", REQUEST_HASH, ACTOR, NOW);

        assertThat(unsigned.expireUnsigned(NOW.plusSeconds(10))).isTrue();
        assertThat(signed.expireUnsigned(NOW.plusSeconds(10))).isFalse();

        assertThat(unsigned.getStatus()).isEqualTo(ContractPartyStatus.EXPIRED);
        assertThat(unsigned.getClosedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(unsigned.isActiveLender()).isFalse();
        assertThat(signed.isActiveLender()).isTrue();
    }

    @Test
    void declineRecordsWhenLenderLeftTheContract() {
        ContractParty party = lender();

        party.decline("DECLINE-KEY", REQUEST_HASH, ACTOR, NOW.plusSeconds(5));

        assertThat(party.getStatus()).isEqualTo(ContractPartyStatus.DECLINED);
        assertThat(party.getClosedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(party.isActiveLender()).isFalse();
    }

    @Test
    void reissueKeepsOriginalConsentTimeOrRequiresSigningAgain() {
        ContractParty carried = lender();
        carried.signMock(TRANSACTION, EVIDENCE_HASH, "INV-KEY-001", REQUEST_HASH, ACTOR, NOW);
        carried.carryOverSignature("FINORA-INV-TX-002", "c".repeat(64), NOW.plusSeconds(60));

        assertThat(carried.getStatus()).isEqualTo(ContractPartyStatus.SIGNED);
        assertThat(carried.getSignedAt()).isEqualTo(NOW);
        assertThat(carried.getSignatureTransactionId()).isEqualTo("FINORA-INV-TX-002");
        assertThat(carried.getSignatureEvidenceHash()).isEqualTo("c".repeat(64));

        ContractParty reset = lender();
        reset.signMock(TRANSACTION, EVIDENCE_HASH, "INV-KEY-001", REQUEST_HASH, ACTOR, NOW);
        reset.resetForReissue(NOW.plusSeconds(60));
        assertThat(reset.getStatus()).isEqualTo(ContractPartyStatus.PENDING_SIGNATURE);
        assertThat(reset.getSignedAt()).isNull();
        assertThat(reset.getIdempotencyKey()).isNull();

        ContractParty pending = lender();
        assertThatThrownBy(() -> pending.carryOverSignature("TX", "c".repeat(64), NOW))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("CONTRACT_PARTY_NOT_SIGNED");
    }

    private ContractParty lender() {
        return ContractParty.lender(
                10L, 101L, ACTOR, new BigDecimal("1000000.00"),
                new BigDecimal("10.000000"), "HOLD-INVESTOR-001", NOW.minusSeconds(60));
    }
}
