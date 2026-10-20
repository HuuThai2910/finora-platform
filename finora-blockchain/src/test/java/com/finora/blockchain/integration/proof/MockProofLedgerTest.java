package com.finora.blockchain.integration.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.blockchain.domain.proof.ProofProviderType;
import com.finora.blockchain.domain.proof.ProofReceiptFormat;
import com.finora.blockchain.domain.proof.ProofType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MockProofLedgerTest {

    private final MockProofLedger ledger = new MockProofLedger();

    @Test
    void returnsDeterministicReceiptExplicitlyMarkedAsMock() {
        ProofLedgerReceipt first = ledger.submit(command());
        ProofLedgerReceipt second = ledger.submit(command());

        assertThat(first).isEqualTo(second);
        assertThat(first.transactionId()).startsWith("MOCK-");
        assertThat(first.blockReference()).isEqualTo("MOCK-BLOCK-NOT-ON-CHAIN");
        assertThat(ProofReceiptFormat.matches(ProofProviderType.MOCK, first.transactionId(), first.blockReference()))
                .isTrue();
        assertThat(ProofReceiptFormat.matches(ProofProviderType.HYPERLEDGER_FABRIC, first.transactionId(),
                first.blockReference())).isFalse();
    }

    @Test
    void mockLedgerRefusesToPretendItHasAChain() {
        assertThat(ledger.provider()).isEqualTo(ProofProviderType.MOCK);
        assertThatThrownBy(() -> ledger.read(UUID.randomUUID()))
                .isInstanceOf(ProofLedgerException.class)
                .extracting("errorCode", "retryable")
                .containsExactly("MOCK_LEDGER_HAS_NO_CHAIN", false);
    }

    private static ProofLedgerCommand command() {
        return new ProofLedgerCommand(
                UUID.fromString("65ec0f3a-df95-44d6-8141-c157b2aa1f1e"),
                "LoanContract", "LC-001", ProofType.CONTRACT_DOCUMENT, "a".repeat(64), 2, "finora-loan");
    }
}
