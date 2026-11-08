package com.finora.loan.domain.contract.entity;

import com.finora.loan.domain.contract.enums.ConsentAction;
import com.finora.loan.domain.contract.enums.ContractDeclineReasonCode;
import com.finora.loan.domain.contract.enums.ContractSignatureProfile;
import com.finora.loan.domain.contract.enums.LoanContractStatus;
import com.finora.loan.domain.contract.enums.SignatureMethod;
import com.finora.loan.domain.contract.enums.SignatureProviderType;
import com.finora.loan.domain.contract.model.LoanContractTerms;
import com.finora.loan.domain.product.enums.RepaymentMethod;
import com.finora.loan.exception.LoanDomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanContractTest {

    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void borrowerSignsExactDocumentOnlyOnce() {
        LoanContract contract = contract(NOW.plusSeconds(3600));

        contract.sign(0, "b".repeat(64), SignatureMethod.CLICK_WRAP_MVP,
                SignatureProviderType.MOCK, "MOCK-TX-001", "e".repeat(64),
                "sign-key", "c".repeat(64), "BORROWER-001", NOW.plusSeconds(10));

        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.SIGNED);
        assertThat(contract.getConsentAction()).isEqualTo(ConsentAction.SIGN);
        assertThat(contract.getSignatureProvider()).isEqualTo(SignatureProviderType.MOCK);
        assertThat(contract.getSignatureTransactionId()).isEqualTo("MOCK-TX-001");
        assertThat(contract.getSignatureEvidenceHash()).isEqualTo("e".repeat(64));
        assertThat(contract.isSameConsent("sign-key", "c".repeat(64), ConsentAction.SIGN)).isTrue();
        assertThatThrownBy(() -> contract.decline(
                0, ContractDeclineReasonCode.OTHER, null, "other-key", "d".repeat(64),
                "BORROWER-001", NOW.plusSeconds(20)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("INVALID_CONTRACT_TRANSITION");
    }

    @Test
    void wrongOwnerOrDocumentHashCannotSign() {
        LoanContract contract = contract(NOW.plusSeconds(3600));

        assertThatThrownBy(() -> contract.sign(
                0, "b".repeat(64), SignatureMethod.CLICK_WRAP_MVP,
                SignatureProviderType.MOCK, "MOCK-TX-001", "e".repeat(64),
                "sign-key", "c".repeat(64), "BORROWER-OTHER", NOW.plusSeconds(10)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("LOAN_CONTRACT_ACCESS_DENIED");

        assertThatThrownBy(() -> contract.sign(
                0, "0".repeat(64), SignatureMethod.CLICK_WRAP_MVP,
                SignatureProviderType.MOCK, "MOCK-TX-001", "e".repeat(64),
                "sign-key", "c".repeat(64), "BORROWER-001", NOW.plusSeconds(10)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("CONTRACT_CONTENT_MISMATCH");
    }

    @Test
    void providerAndMethodMustMatch() {
        LoanContract contract = contract(NOW.plusSeconds(3600));

        assertThatThrownBy(() -> contract.sign(
                0, "b".repeat(64), SignatureMethod.VNPT_SMART_CA,
                SignatureProviderType.MOCK, "MOCK-TX-001", "e".repeat(64),
                "sign-key", "c".repeat(64), "BORROWER-001", NOW.plusSeconds(10)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("SIGNATURE_METHOD_PROVIDER_MISMATCH");
    }

    @Test
    void smartCaSignatureCompletesOnlyForStoredTransactionAndDocument() {
        LoanContract contract = contract(NOW.plusSeconds(3600));

        contract.beginDigitalSignature(
                0, "b".repeat(64), "FINORA-TX-001", "FINORA-DOC-001",
                "sign-key", "c".repeat(64), "BORROWER-001", NOW.plusSeconds(10));

        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.SIGNING);
        assertThat(contract.getSignatureProvider()).isEqualTo(SignatureProviderType.VNPT_SMART_CA);
        assertThat(contract.getSignatureDocumentId()).isEqualTo("FINORA-DOC-001");

        assertThatThrownBy(() -> contract.completeDigitalSignature(
                "FINORA-TX-OTHER", "FINORA-DOC-001", "e".repeat(64),
                "BORROWER-001", NOW.plusSeconds(20)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("SIGNATURE_EVIDENCE_MISMATCH");

        contract.completeDigitalSignature(
                "FINORA-TX-001", "FINORA-DOC-001", "e".repeat(64),
                "BORROWER-001", NOW.plusSeconds(20));

        contract.completeDigitalSignature(
                "FINORA-TX-001", "FINORA-DOC-001", "e".repeat(64),
                "BORROWER-001", NOW.plusSeconds(30));

        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.SIGNED);
        assertThat(contract.getSignatureEvidenceHash()).isEqualTo("e".repeat(64));
    }

    @Test
    void rejectedSmartCaAttemptReturnsToPendingWithoutKeepingAttemptIdentifiers() {
        LoanContract contract = contract(NOW.plusSeconds(3600));
        contract.beginDigitalSignature(
                0, "b".repeat(64), "FINORA-TX-001", "FINORA-DOC-001",
                "sign-key", "c".repeat(64), "BORROWER-001", NOW.plusSeconds(10));

        contract.resetRejectedDigitalSignature("BORROWER-001", NOW.plusSeconds(20));

        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.PENDING_SIGNATURE);
        assertThat(contract.getSignatureProvider()).isNull();
        assertThat(contract.getSignatureTransactionId()).isNull();
        assertThat(contract.getSignatureDocumentId()).isNull();
        assertThat(contract.getConsentIdempotencyKey()).isNull();
    }

    @Test
    void expiryIsIdempotentAndPreventsConsent() {
        LoanContract contract = contract(NOW.plusSeconds(60));

        assertThat(contract.expireIfDue(NOW.plusSeconds(59))).isFalse();
        assertThat(contract.expireIfDue(NOW.plusSeconds(60))).isTrue();
        assertThat(contract.expireIfDue(NOW.plusSeconds(61))).isFalse();
        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.EXPIRED);
    }

    @Test
    void multiPartyContractRequiresLendersBeforeBorrowerAndActivatesAfterBorrower() {
        LoanContract contract = contract(NOW.plusSeconds(3600));
        contract.initializeMultiParty();

        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.PENDING_LENDER_SIGNATURES);
        assertThatThrownBy(() -> contract.sign(
                0, "b".repeat(64), SignatureMethod.CLICK_WRAP_MVP,
                SignatureProviderType.MOCK, "MOCK-TX-001", "e".repeat(64),
                "sign-key", "c".repeat(64), "BORROWER-001", NOW.plusSeconds(10)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("INVALID_CONTRACT_TRANSITION");

        contract.markBorrowerSignatureReady("SYSTEM-LENDER-SIGNATURES", NOW.plusSeconds(20));
        contract.sign(0, "b".repeat(64), SignatureMethod.CLICK_WRAP_MVP,
                SignatureProviderType.MOCK, "MOCK-TX-001", "e".repeat(64),
                "sign-key", "c".repeat(64), "BORROWER-001", NOW.plusSeconds(30));
        contract.activateAfterAllSignatures("SYSTEM-CONTRACT-ACTIVATION", NOW.plusSeconds(30));

        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.EFFECTIVE);
        assertThat(contract.getEffectiveAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void borrowerMayDeclineWhileLendersAreStillSigning() {
        LoanContract contract = contract(NOW.plusSeconds(3600));
        contract.initializeMultiParty();

        contract.decline(
                0, ContractDeclineReasonCode.BORROWER_CHANGED_MIND, null,
                "decline-key", "d".repeat(64), "BORROWER-001", NOW.plusSeconds(10));

        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.DECLINED);
        assertThat(contract.getDeclinedBy()).isEqualTo("BORROWER-001");
    }

    @Test
    void investorWithdrawalReopensFundingInsteadOfClosingContract() {
        LoanContract contract = contract(NOW.plusSeconds(3600));
        contract.initializeMultiParty();

        contract.reopenForRefunding("INVESTOR-001", NOW.plusSeconds(10));

        // FUNDING-REOPEN-001: hợp đồng chờ gọi vốn bổ sung, không mang consent cấp hợp đồng của nhà đầu tư.
        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.PENDING_REFUNDING);
        assertThat(contract.getConsentAction()).isNull();
        assertThat(contract.getDeclinedBy()).isNull();
        assertThat(contract.isDueAt(NOW.plusSeconds(7200))).isFalse();
        assertThatThrownBy(() -> contract.reopenForRefunding("INVESTOR-002", NOW.plusSeconds(20)))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("LENDER_SIGNATURE_WINDOW_CLOSED");
    }

    @Test
    void refundedContractIsReissuedWithNewDocumentAndSignatureWindow() {
        LoanContract contract = contract(NOW.plusSeconds(3600));
        contract.initializeMultiParty();
        contract.reopenForRefunding("SYSTEM", NOW.plusSeconds(10));
        Instant reissuedExpiry = NOW.plusSeconds(86_400);

        contract.reissueAfterRefunding("Văn bản phát hành lại", "f".repeat(64), reissuedExpiry, "SYSTEM",
                NOW.plusSeconds(60));

        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.PENDING_LENDER_SIGNATURES);
        assertThat(contract.getDocumentHash()).isEqualTo("f".repeat(64));
        assertThat(contract.getExpiresAt()).isEqualTo(reissuedExpiry);
    }

    @Test
    void borrowerMayStopRefundingContractEvenAfterOldSignatureWindowPassed() {
        LoanContract contract = contract(NOW.plusSeconds(3600));
        contract.initializeMultiParty();
        contract.reopenForRefunding("SYSTEM", NOW.plusSeconds(10));

        contract.decline(0, ContractDeclineReasonCode.BORROWER_CHANGED_MIND, null,
                "withdraw-key", "d".repeat(64), "BORROWER-001", NOW.plusSeconds(7200));

        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.DECLINED);
    }

    @Test
    void refundingContractExpiresOnlyWhenFundingRoundExpires() {
        LoanContract contract = contract(NOW.plusSeconds(3600));
        contract.initializeMultiParty();
        assertThat(contract.expireWhileRefunding(NOW.plusSeconds(10))).isFalse();
        contract.reopenForRefunding("SYSTEM", NOW.plusSeconds(10));

        assertThat(contract.expireWhileRefunding(NOW.plusSeconds(20))).isTrue();
        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.EXPIRED);
    }

    @Test
    void padesProfileIsChosenOnlyForNewMultiPartyContractsAndDefaultsToDetachedEvidence() {
        LoanContract single = contract(NOW.plusSeconds(3600));
        assertThat(single.getSignatureProfile()).isEqualTo(ContractSignatureProfile.DETACHED_EVIDENCE);
        assertThat(single.isPadesSigned()).isFalse();
        // Hợp đồng một bên kiểu LN-008 không ký PAdES.
        assertThatThrownBy(single::usePadesSignatures)
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("SIGNATURE_PROFILE_LOCKED");

        LoanContract multi = contract(NOW.plusSeconds(3600));
        multi.initializeMultiParty();
        multi.usePadesSignatures();

        assertThat(multi.getSignatureProfile()).isEqualTo(ContractSignatureProfile.PADES_B_B);
        assertThat(multi.isPadesSigned()).isTrue();
    }

    @Test
    void systemExpiresOnlyAnOpenDigitalSignatureSessionAndReturnsToBorrowerTurn() {
        LoanContract contract = contract(NOW.plusSeconds(3600));
        contract.initializeMultiParty();
        contract.markBorrowerSignatureReady("SYSTEM-LENDER-SIGNATURES", NOW.plusSeconds(5));
        assertThat(contract.expireDigitalSignatureSession(NOW.plusSeconds(6))).isFalse();
        contract.beginDigitalSignature(
                0, "b".repeat(64), "FINORA-PADES-TX-001", "FINORA-DOC-001",
                "sign-key", "c".repeat(64), "BORROWER-001", NOW.plusSeconds(10));

        assertThat(contract.expireDigitalSignatureSession(NOW.plusSeconds(700))).isTrue();

        assertThat(contract.getStatus()).isEqualTo(LoanContractStatus.PENDING_BORROWER_SIGNATURE);
        assertThat(contract.getSignatureTransactionId()).isNull();
        assertThat(contract.getConsentIdempotencyKey()).isNull();
        assertThat(contract.getUpdatedBy()).isEqualTo("SYSTEM");
    }

    private LoanContract contract(Instant expiresAt) {
        return LoanContract.create(
                "LC-ABCDEF0123456789ABCD",
                1L,
                "BORROWER-001",
                new LoanContractTerms(
                        new BigDecimal("50000000.00"), 12, new BigDecimal("15.0000"),
                        RepaymentMethod.ANNUITY, 2L, new BigDecimal("4000000.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("54000000.00"),
                        new BigDecimal("4500000.00"), new BigDecimal("4500000.00"),
                        "a".repeat(64), LocalDate.of(2026, 8, 20)
                ),
                "LOAN_TERMS_V1",
                "CLICK_WRAP_TEXT_V1",
                "Nội dung hợp đồng",
                "b".repeat(64),
                expiresAt,
                "ADMIN-001",
                NOW
        );
    }
}
