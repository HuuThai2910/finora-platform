package com.finora.blockchain.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finora.blockchain.domain.proof.ProofProviderType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProofSubmissionPropertiesTest {

    @Test
    void appliesSafeDefaults() {
        ProofSubmissionProperties properties = new ProofSubmissionProperties(null, 0, 0, null, null, null);

        assertThat(properties.provider()).isEqualTo(ProofProviderType.MOCK);
        assertThat(properties.batchSize()).isEqualTo(100);
        assertThat(properties.maxAttempts()).isEqualTo(10);
        assertThat(properties.processingLease()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void rejectsMaximumBackoffBelowInitialBackoff() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProofSubmissionProperties(
                ProofProviderType.MOCK, 10, 3, Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofMinutes(1)));
    }
}
