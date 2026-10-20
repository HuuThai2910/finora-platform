package com.finora.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** D37: provider tiền mock và khóa dev mặc định bị liệt kê để staging/production từ chối khởi động. */
class PaymentSimulatedSettingsTest {

    private final PaymentSimulatedSettings check = new PaymentSimulatedSettings();

    @Test
    void defaultMockProvidersAndDevAccountKeyAreReported() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("finora.payment.withdrawal.account-encryption-key", PaymentSimulatedSettings.DEV_ACCOUNT_KEY);

        assertThat(check.simulatedSettings(environment)).containsExactly(
                "finora.payment.disbursement.provider=mock", "finora.payment.top-up.provider=mock",
                "finora.payment.payout.provider=mock",
                "finora.payment.withdrawal.account-encryption-key (khóa dev mặc định)");
    }

    @Test
    void realProvidersAndKeysAreNotReported() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("finora.payment.disbursement.provider", "bank")
                .withProperty("finora.payment.top-up.provider", "zalopay")
                .withProperty("finora.payment.payout.provider", "bank")
                .withProperty("finora.payment.withdrawal.account-encryption-key", "a-key-from-the-secret-store");

        assertThat(check.simulatedSettings(environment)).isEmpty();
    }
}
