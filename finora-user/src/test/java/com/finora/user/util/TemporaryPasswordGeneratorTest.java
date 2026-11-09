package com.finora.user.util;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TemporaryPasswordGeneratorTest {

    @RepeatedTest(50)
    void passwordHasEveryCharacterGroupAndOnlySafeCharacters() {
        String password = TemporaryPasswordGenerator.generate();

        assertThat(password).hasSize(TemporaryPasswordGenerator.LENGTH);
        assertThat(password.chars().anyMatch(c -> TemporaryPasswordGenerator.UPPER.indexOf(c) >= 0)).isTrue();
        assertThat(password.chars().anyMatch(c -> TemporaryPasswordGenerator.LOWER.indexOf(c) >= 0)).isTrue();
        assertThat(password.chars().anyMatch(c -> TemporaryPasswordGenerator.DIGITS.indexOf(c) >= 0)).isTrue();
        assertThat(password.chars().anyMatch(c -> TemporaryPasswordGenerator.SPECIAL.indexOf(c) >= 0)).isTrue();
        // Khớp đúng bảng ký tự finora-notification chấp nhận; không có ký tự dễ nhầm khi chép tay.
        assertThat(password).matches("[A-Za-z0-9!@#$%*\\-_+=?]{12,64}").doesNotContainPattern("[0Oo1lI]");
    }

    @Test
    void passwordsDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(TemporaryPasswordGenerator.generate());
        }
        assertThat(seen).hasSize(1000);
    }
}
