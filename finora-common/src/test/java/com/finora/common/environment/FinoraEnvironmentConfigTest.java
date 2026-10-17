package com.finora.common.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class FinoraEnvironmentConfigTest {

    private final FinoraEnvironmentConfig config = new FinoraEnvironmentConfig();

    @Test
    void defaultsToLocalAndAllowsDevSettingsThere() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("finora.pin.token-secret", FinoraEnvironmentConfig.DEV_PIN_TOKEN_SECRET);

        assertThat(config.finoraEnvironment(environment, checks())).isEqualTo(FinoraEnvironment.LOCAL);
    }

    @Test
    void productionRefusesTheRepositoryPinSecretAndE2eSwitchesWithoutPrintingValues() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("finora.environment", "production")
                .withProperty("finora.pin.token-secret", FinoraEnvironmentConfig.DEV_PIN_TOKEN_SECRET)
                .withProperty("finora.e2e.clock-offset", "P31D")
                .withProperty("finora.e2e.pause-point", "core-booked-before-save");

        assertThatThrownBy(() -> config.finoraEnvironment(environment, checks()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finora.pin.token-secret")
                .hasMessageContaining("finora.e2e.clock-offset")
                .hasMessageContaining("finora.e2e.pause-point")
                .hasMessageNotContaining(FinoraEnvironmentConfig.DEV_PIN_TOKEN_SECRET);
    }

    @Test
    void stagingRefusesServiceSpecificMocks() {
        MockEnvironment environment = new MockEnvironment().withProperty("finora.environment", "staging")
                .withProperty("finora.pin.token-secret", "a-real-secret-from-the-vault-0123456789");

        assertThatThrownBy(() -> config.finoraEnvironment(environment,
                checks(env -> List.of("finora.payment.top-up.provider=mock"))))
                .hasMessageContaining("finora.payment.top-up.provider=mock");
    }

    @Test
    void productionWithRealSettingsStarts() {
        MockEnvironment environment = new MockEnvironment().withProperty("finora.environment", "PRODUCTION")
                .withProperty("finora.pin.token-secret", "a-real-secret-from-the-vault-0123456789")
                .withProperty("finora.e2e.clock-offset", "PT0S")
                .withProperty("finora.e2e.pause-point", "");

        assertThat(config.finoraEnvironment(environment, checks(env -> List.of())))
                .isEqualTo(FinoraEnvironment.PRODUCTION);
    }

    @Test
    void unknownEnvironmentNameFailsFast() {
        assertThatThrownBy(() -> FinoraEnvironment.parse("prod"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("production");
    }

    private static org.springframework.beans.factory.ObjectProvider<SimulatedSettingsCheck> checks(
            SimulatedSettingsCheck... checks) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < checks.length; i++) {
            factory.registerSingleton("check" + i, checks[i]);
        }
        return factory.getBeanProvider(SimulatedSettingsCheck.class);
    }
}
