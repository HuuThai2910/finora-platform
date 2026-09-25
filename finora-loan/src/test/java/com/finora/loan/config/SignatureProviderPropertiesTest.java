package com.finora.loan.config;

import com.finora.loan.domain.contract.SignatureProviderType;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignatureProviderPropertiesTest {

    @Test
    void defaultsToMockWithoutProviderSecrets() {
        SignatureProviderProperties properties = new SignatureProviderProperties(null, null);

        assertThat(properties.provider()).isEqualTo(SignatureProviderType.MOCK);
    }

    @Test
    void smartCaFailsClosedWithoutHttpsAndUatSignerConfiguration() {
        assertThatThrownBy(() -> new SignatureProviderProperties(
                SignatureProviderType.VNPT_SMART_CA,
                new SignatureProviderProperties.VnptSmartCa(
                        URI.create("http://localhost"), "sp", "password",
                        true, "test-user", "test-serial", Duration.ofSeconds(3), Duration.ofSeconds(15))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");

        assertThatThrownBy(() -> new SignatureProviderProperties(
                SignatureProviderType.VNPT_SMART_CA,
                new SignatureProviderProperties.VnptSmartCa(
                        URI.create("https://rmgateway.vnptit.vn/sca/sp769"), "", "password",
                        true, "test-user", "test-serial", Duration.ofSeconds(3), Duration.ofSeconds(15))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceProviderId");

        assertThatThrownBy(() -> new SignatureProviderProperties(
                SignatureProviderType.VNPT_SMART_CA,
                new SignatureProviderProperties.VnptSmartCa(
                        URI.create("https://rmgateway.vnptit.vn/sca/sp769"), "sp", "password",
                        false, "test-user", "test-serial", Duration.ofSeconds(3), Duration.ofSeconds(15))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixed signer");
    }
}
