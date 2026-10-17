package com.finora.blockchain.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finora.blockchain.domain.proof.ProofProviderType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProofSubmissionPropertiesTest {

    @Test
    void appliesSafeDefaults() {
        ProofSubmissionProperties properties = new ProofSubmissionProperties(null, 0, 0, null, null, null, null);

        assertThat(properties.provider()).isEqualTo(ProofProviderType.MOCK);
        assertThat(properties.batchSize()).isEqualTo(100);
        assertThat(properties.maxAttempts()).isEqualTo(10);
        assertThat(properties.processingLease()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.chainCheckInterval()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void rejectsMaximumBackoffBelowInitialBackoff() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProofSubmissionProperties(
                ProofProviderType.MOCK, 10, 3, Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofMinutes(1),
                null));
    }

    @Test
    void fabricDefaultsMatchTheLocalNetworkAndRejectNonPositiveTimeouts() {
        FabricGatewayProperties fabric = new FabricGatewayProperties(
                " ", null, null, null, null, "", null, null, null, null, null, null);

        assertThat(fabric.peerEndpoint()).isEqualTo("localhost:7051");
        assertThat(fabric.mspId()).isEqualTo("Org1MSP");
        assertThat(fabric.channelName()).isEqualTo("finora-channel");
        assertThat(fabric.chaincodeName()).isEqualTo("finora-ledger");
        assertThat(fabric.certPath()).isNull();
        assertThat(fabric.commitStatusTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThatIllegalArgumentException().isThrownBy(() -> new FabricGatewayProperties(
                null, null, null, null, null, null, null, null, Duration.ZERO, null, null, null));
    }
}
