package com.finora.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** D37: provider tiền mock bị liệt kê để staging/production từ chối khởi động. */
class PaymentSimulatedSettingsTest {

    private final PaymentSimulatedSettings check = new PaymentSimulatedSettings();

    @Test
    void defaultMockProvidersAreReported() {
        assertThat(check.simulatedSettings(new MockEnvironment())).containsExactly(
                "finora.payment.disbursement.provider=mock", "finora.payment.top-up.provider=mock");
    }

    @Test
    void realProvidersAreNotReported() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("finora.payment.disbursement.provider", "bank")
                .withProperty("finora.payment.top-up.provider", "zalopay");

        assertThat(check.simulatedSettings(environment)).isEmpty();
    }
}
