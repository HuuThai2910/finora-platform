package com.finora.loan.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PadesSignaturePropertiesTest {

    @Test
    void defaultsEnablePadesWithTenMinuteSessionAboveSmartCaExpiry() {
        PadesSignatureProperties properties = new PadesSignatureProperties(null, null, null);

        assertThat(properties.isEnabled()).isTrue();
        // SmartCA hết hạn giao dịch sau khoảng 300 giây; phiên FINORA phải dài hơn để không đóng sớm.
        assertThat(properties.signingSessionTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.workerBatchSize()).isEqualTo(20);
        assertThat(new PadesSignatureProperties(false, null, null).isEnabled()).isFalse();
    }

    @Test
    void rejectsTimeoutShorterThanOneMinuteAndUnboundedBatches() {
        assertThatThrownBy(() -> new PadesSignatureProperties(true, Duration.ofSeconds(30), 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PadesSignatureProperties(true, Duration.ofMinutes(10), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PadesSignatureProperties(true, Duration.ofMinutes(10), 500))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
