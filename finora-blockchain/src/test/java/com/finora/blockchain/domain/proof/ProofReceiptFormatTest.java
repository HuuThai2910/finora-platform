package com.finora.blockchain.domain.proof;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProofReceiptFormatTest {

    private static final String FABRIC_TX = "0123456789abcdef".repeat(4);

    @Test
    void mockReceiptOnlyFitsMockProvider() {
        assertThat(ProofReceiptFormat.matches(ProofProviderType.MOCK, "MOCK-1", "MOCK-BLOCK-NOT-ON-CHAIN")).isTrue();
        assertThat(ProofReceiptFormat.matches(ProofProviderType.HYPERLEDGER_FABRIC, "MOCK-1", "MOCK-BLOCK-NOT-ON-CHAIN"))
                .isFalse();
        assertThat(ProofReceiptFormat.matches(ProofProviderType.MOCK, "MOCK-1", "finora-channel/3")).isFalse();
    }

    @Test
    void fabricReceiptNeedsSixtyFourHexTransactionIdAndOptionalChannelBlock() {
        assertThat(ProofReceiptFormat.matches(ProofProviderType.HYPERLEDGER_FABRIC, FABRIC_TX, "finora-channel/12")).isTrue();
        assertThat(ProofReceiptFormat.matches(ProofProviderType.HYPERLEDGER_FABRIC, FABRIC_TX, null)).isTrue();
        assertThat(ProofReceiptFormat.matches(ProofProviderType.MOCK, FABRIC_TX, "finora-channel/12")).isFalse();
        assertThat(ProofReceiptFormat.matches(ProofProviderType.HYPERLEDGER_FABRIC, FABRIC_TX.toUpperCase(), null)).isFalse();
        assertThat(ProofReceiptFormat.matches(ProofProviderType.HYPERLEDGER_FABRIC, FABRIC_TX, "block 12")).isFalse();
    }

    @Test
    void blockReferenceIsChannelAndUnsignedBlockNumber() {
        assertThat(ProofReceiptFormat.fabricBlockReference("finora-channel", 5)).isEqualTo("finora-channel/5");
    }
}
