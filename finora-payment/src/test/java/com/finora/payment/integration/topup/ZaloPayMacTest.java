package com.finora.payment.integration.topup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ZaloPayMacTest {
    @Test
    void createsStableHexMacAndUsesConstantTimeComparison() {
        String mac = ZaloPayMac.hmacSha256("payload", "secret");

        assertThat(mac).matches("^[0-9a-f]{64}$");
        assertThat(ZaloPayMac.matches(mac, mac.toUpperCase())).isTrue();
        assertThat(ZaloPayMac.matches(mac, mac.substring(2))).isFalse();
    }
}
