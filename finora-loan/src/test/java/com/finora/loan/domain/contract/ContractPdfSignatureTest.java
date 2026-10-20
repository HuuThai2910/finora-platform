package com.finora.loan.domain.contract;

import com.finora.loan.exception.LoanDomainException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractPdfSignatureTest {

    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");
    private static final String PREFIX = "%PDF-1.4 revision-bytes /Contents ";
    private static final String PLACEHOLDER = "<" + "0".repeat(20) + ">";
    private static final String SUFFIX = " trailer %%EOF\n";

    @Test
    void preparedSignatureIsSignedOnlyWhenBytesOutsideContentsStayIdentical() {
        ContractPdfSignature signature = ContractPdfSignature.prepare(preparation(SignatureProviderType.MOCK,
                SignatureMethod.CLICK_WRAP_MVP), NOW);
        assertThat(signature.isPrepared()).isTrue();
        assertThat(signature.isSessionExpired(NOW.plus(Duration.ofMinutes(9)), Duration.ofMinutes(10))).isFalse();
        assertThat(signature.isSessionExpired(NOW.plus(Duration.ofMinutes(10)), Duration.ofMinutes(10))).isTrue();

        byte[] tampered = content();
        tampered[0] = 'X';
        assertThatThrownBy(() -> signature.markSigned(tampered, "1".repeat(64), "2".repeat(64), "3".repeat(64), NOW))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("PADES_SIGNED_CONTENT_MISMATCH");
        assertThatThrownBy(() -> signature.markSigned(
                Arrays.copyOf(content(), content().length + 1), "1".repeat(64), "2".repeat(64), "3".repeat(64), NOW))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("PADES_SIGNED_CONTENT_MISMATCH");

        byte[] embedded = content();
        System.arraycopy("ABCDEF".getBytes(StandardCharsets.US_ASCII), 0, embedded, PREFIX.length() + 1, 6);
        signature.markSigned(embedded, "1".repeat(64), "2".repeat(64), "3".repeat(64), NOW.plusSeconds(5));

        assertThat(signature.isSigned()).isTrue();
        assertThat(signature.contentCopy()).isEqualTo(embedded);
        assertThat(signature.getContentHash()).isEqualTo("1".repeat(64));
        assertThat(signature.getEvidenceHash()).isEqualTo("3".repeat(64));
        assertThat(signature.getSignedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(signature.isSessionExpired(NOW.plus(Duration.ofHours(1)), Duration.ofMinutes(10))).isFalse();
        assertThatThrownBy(() -> signature.abandon(ContractPdfSignatureAbandonReason.CONTRACT_CLOSED, NOW))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("PADES_SIGNATURE_NOT_PREPARED");
    }

    @Test
    void abandonedSignatureNeverJoinsTheChain() {
        ContractPdfSignature signature = ContractPdfSignature.prepare(preparation(
                SignatureProviderType.VNPT_SMART_CA, SignatureMethod.VNPT_SMART_CA), NOW);

        signature.abandon(ContractPdfSignatureAbandonReason.PROVIDER_REJECTED, NOW.plusSeconds(30));

        assertThat(signature.getStatus()).isEqualTo(ContractPdfSignatureStatus.ABANDONED);
        assertThat(signature.getAbandonReason()).isEqualTo(ContractPdfSignatureAbandonReason.PROVIDER_REJECTED);
        assertThatThrownBy(() -> signature.markSigned(content(), "1".repeat(64), "2".repeat(64), "3".repeat(64), NOW))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("PADES_SIGNATURE_NOT_PREPARED");
    }

    @Test
    void providerAndMethodMustBeAConsistentPair() {
        assertThatThrownBy(() -> ContractPdfSignature.prepare(
                preparation(SignatureProviderType.MOCK, SignatureMethod.VNPT_SMART_CA), NOW))
                .isInstanceOf(LoanDomainException.class)
                .extracting("code").isEqualTo("SIGNATURE_METHOD_PROVIDER_MISMATCH");
    }

    private static byte[] content() {
        return (PREFIX + PLACEHOLDER + SUFFIX).getBytes(StandardCharsets.US_ASCII);
    }

    private static ContractPdfSignature.Preparation preparation(SignatureProviderType provider, SignatureMethod method) {
        return new ContractPdfSignature.Preparation(
                10L, ContractPartyType.LENDER, "INVESTOR-001", 1, 1, provider, method,
                "MOCK-PADES-TX-001", "FINORA-DOC-001", "sign-key-001", "a".repeat(64), "b".repeat(64),
                content(), "c".repeat(64), PREFIX.length(), PLACEHOLDER.length(), "d".repeat(64),
                new byte[] {0x31, 0x00}, "e".repeat(64), NOW, new byte[] {0x30, 0x00}, null,
                "1a2b", "f".repeat(64));
    }
}
