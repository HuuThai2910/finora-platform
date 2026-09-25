package com.finora.blockchain.integration.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.blockchain.domain.proof.ProofType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MockProofLedgerTest {

    @Test
    void returnsDeterministicReceiptExplicitlyMarkedAsMock() {
        ProofLedgerCommand command = new ProofLedgerCommand(
                UUID.fromString("65ec0f3a-df95-44d6-8141-c157b2aa1f1e"),
                "LOAN_CONTRACT", "LC-001", ProofType.CONTRACT_DOCUMENT, "a".repeat(64), 1);
        MockProofLedger ledger = new MockProofLedger();

        ProofLedgerReceipt first = ledger.submit(command);
        ProofLedgerReceipt second = ledger.submit(command);

        assertThat(first).isEqualTo(second);
        assertThat(first.transactionId()).startsWith("MOCK-");
        assertThat(first.blockReference()).isEqualTo("MOCK-BLOCK-NOT-ON-CHAIN");
    }

    @Test
    void fabricProviderFailsClosedUntilRealAdapterIsReady() {
        assertThatThrownBy(() -> new HyperledgerFabricProofLedger().submit(command()))
                .isInstanceOf(ProofLedgerException.class)
                .extracting("errorCode", "retryable")
                .containsExactly("FABRIC_INTEGRATION_NOT_READY", false);
    }

    private static ProofLedgerCommand command() {
        return new ProofLedgerCommand(
                UUID.fromString("65ec0f3a-df95-44d6-8141-c157b2aa1f1e"),
                "LOAN_CONTRACT", "LC-001", ProofType.CONTRACT_DOCUMENT, "a".repeat(64), 1);
    }
}
