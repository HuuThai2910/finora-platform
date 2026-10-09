package com.finora.user.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PinStrengthTest {

    @ParameterizedTest
    @ValueSource(strings = {"000000", "999999", "123456", "012345", "654321", "987654", "123123", "12345", "12a456"})
    void pinDeDoanHoacSaiDinhDangBiChan(String pin) {
        assertThat(PinStrength.isWeak(pin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"394817", "582916", "100293"})
    void pinNgauNhienDuocChapNhan(String pin) {
        assertThat(PinStrength.isWeak(pin)).isFalse();
    }
}
