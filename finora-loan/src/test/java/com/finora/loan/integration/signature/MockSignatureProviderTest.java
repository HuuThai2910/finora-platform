package com.finora.loan.integration.signature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.support.HashingService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockSignatureProviderTest {

    private final MockSignatureProvider provider = new MockSignatureProvider(
            new HashingService(new ObjectMapper()));

    @Test
    void createsDeterministicClickWrapEvidenceWithoutPretendingToBeSmartCa() {
        SignatureCommand command = command(SignatureMethod.CLICK_WRAP_MVP);

        SignatureSubmission first = provider.submit(command);
        SignatureSubmission second = provider.submit(command);

        assertThat(first).isEqualTo(second);
        assertThat(provider.type()).isEqualTo(SignatureProviderType.MOCK);
        assertThat(provider.method()).isEqualTo(SignatureMethod.CLICK_WRAP_MVP);
        assertThat(first.status()).isEqualTo(SignatureSubmissionStatus.COMPLETED);
        assertThat(first.providerTransactionId()).matches("MOCK-[0-9a-f]{32}");
        assertThat(first.documentId()).isEqualTo("FINORA-doc-001");
        assertThat(first.signatureValue()).matches("[0-9a-f]{64}");
    }

    @Test
    void refusesSmartCaMethod() {
        assertThatThrownBy(() -> provider.submit(command(SignatureMethod.VNPT_SMART_CA)))
                .isInstanceOf(LoanBusinessException.class)
                .extracting("code")
                .isEqualTo("SIGNATURE_METHOD_PROVIDER_MISMATCH");
    }

    @Test
    void padesDigestIsSignedByAnEphemeralSelfSignedDemoCertificateWithoutLegalValue() throws Exception {
        SigningCertificate demo = provider.signingCertificate("INVESTOR-001");
        java.security.cert.X509Certificate certificate = demo.certificate();
        assertThat(certificate.getSubjectX500Principal().getName())
                .contains("CN=FINORA DEMO - KHONG CO GIA TRI PHAP LY");
        assertThat(certificate.getSubjectX500Principal()).isEqualTo(certificate.getIssuerX500Principal());
        certificate.verify(certificate.getPublicKey());
        assertThat(certificate.getBasicConstraints()).isEqualTo(-1);
        assertThat(certificate.getKeyUsage()[0]).isTrue();
        assertThat(demo.chain()).isEmpty();
        // Khóa demo sinh một lần cho mỗi tiến trình, dùng chung cho mọi bên ký của chế độ MOCK.
        assertThat(provider.signingCertificate("BORROWER-001").certificate()).isEqualTo(certificate);

        byte[] signedAttributes = "signed-attributes-der".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(signedAttributes));
        SignatureSubmission submission = provider.submitDigest(digestCommand(digest, SignatureMethod.CLICK_WRAP_MVP));

        assertThat(provider.completesSynchronously()).isTrue();
        assertThat(submission.status()).isEqualTo(SignatureSubmissionStatus.COMPLETED);
        assertThat(submission.providerTransactionId()).isEqualTo("MOCK-PADES-TX-001");
        // Ký digest qua DigestInfo phải tương đương SHA256withRSA trên dữ liệu gốc để CMS kiểm được.
        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(signedAttributes);
        assertThat(verifier.verify(java.util.Base64.getDecoder().decode(submission.signatureValue()))).isTrue();
        assertThatThrownBy(() -> provider.submitDigest(digestCommand(digest, SignatureMethod.VNPT_SMART_CA)))
                .isInstanceOf(LoanBusinessException.class)
                .extracting("code")
                .isEqualTo("SIGNATURE_METHOD_PROVIDER_MISMATCH");
    }

    private DigestSignatureCommand digestCommand(String digest, SignatureMethod method) {
        return new DigestSignatureCommand(
                "LC-ABCDEF0123456789ABCD", "INVESTOR-001", digest, "MOCK-PADES-TX-001", "FINORA-DOC-001",
                "FINORA LC-ABCDEF0123456789ABCD - chu ky 1", method);
    }

    private SignatureCommand command(SignatureMethod method) {
        return new SignatureCommand(
                "LC-ABCDEF0123456789ABCD",
                "BORROWER-001",
                "a".repeat(64),
                "b".repeat(64),
                "sign-key-001",
                "FINORA-tx-001",
                "FINORA-doc-001",
                method
        );
    }
}
