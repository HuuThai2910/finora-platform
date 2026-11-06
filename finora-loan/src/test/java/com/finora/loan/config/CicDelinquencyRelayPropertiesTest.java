package com.finora.loan.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CicDelinquencyRelayPropertiesTest {

    @Test
    void disabledRelayDoesNotRequireSecret() {
        CicDelinquencyRelayProperties properties = new CicDelinquencyRelayProperties(
                false, null, null, null, null, 0, null, null, 0, null, null, null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.internalApiKey()).isEmpty();
        assertThat(properties.maxAttempts()).isEqualTo(5);
        assertThat(properties.dltTopic()).isEqualTo("finora.loan.delinquency-changed.cic.DLT");
    }

    @Test
    void enabledRelayFailsClosedWithoutSecret() {
        assertThatThrownBy(() -> new CicDelinquencyRelayProperties(
                true, "http://cic.test", " ", Duration.ofSeconds(1), Duration.ofSeconds(1),
                3, Duration.ofSeconds(1), Duration.ofSeconds(3), 2, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("internal-api-key");
    }
}
