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
