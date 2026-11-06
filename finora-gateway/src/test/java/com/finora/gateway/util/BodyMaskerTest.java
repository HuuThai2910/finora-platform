package com.finora.gateway.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BodyMaskerTest {

    private final BodyMasker masker = new BodyMasker(new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {"password", "newPassword", "currentPin", "pin", "pinToken", "otp", "otpCode",
            "accessToken", "refresh_token", "clientSecret", "frontImage", "selfie", "faceImageBase64", "frames",
            "signature", "Authorization"})
    void sensitiveKeys(String key) {
        assertThat(BodyMasker.isSensitive(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "amount", "shippingAddress", "loanApplicationId", "idempotencyKey", "spinner",
            "documentNumber", "message"})
    void ordinaryKeys(String key) {
        assertThat(BodyMasker.isSensitive(key)).isFalse();
    }

    @Test
    void masksNestedJsonAndShortensLongStrings() {
        String longValue = "A".repeat(500);
        String body = "{\"email\":\"a@b.vn\",\"password\":\"Secret123\",\"profile\":{\"pin\":\"123456\",\"note\":\""
                + longValue + "\"},\"items\":[{\"otp\":\"999\"}]}";

        String masked = masker.mask(body, "application/json", false, 3000);

        assertThat(masked)
                .contains("\"email\":\"a@b.vn\"")
                .contains("\"password\":\"***\"")
                .contains("\"pin\":\"***\"")
                .contains("\"otp\":\"***\"")
                .contains("(500 ký tự)")
                .doesNotContain("Secret123", "123456", longValue);
    }

    @Test
    void masksTruncatedJsonByPattern() {
        String body = "{\"email\":\"a@b.vn\",\"password\":\"Secret123\",\"note\":\"abc";

        String masked = masker.mask(body, "application/json", true, 3000);

        assertThat(masked).contains("\"password\":\"***\"").doesNotContain("Secret123").endsWith("…(đã cắt)");
    }

    @Test
    void masksFormAndQuery() {
        assertThat(masker.mask("username=hai&password=abc", "application/x-www-form-urlencoded", false, 3000))
                .isEqualTo("username=hai&password=***");
        assertThat(masker.maskQuery("page=0&token=xyz&size=20")).isEqualTo("page=0&token=***&size=20");
        assertThat(masker.maskQuery(null)).isNull();
    }

    @Test
    void cutsToStoredLimit() {
        String masked = masker.mask("x".repeat(100), "text/plain", false, 10);

        assertThat(masked).isEqualTo("xxxxxxxxxx …(đã cắt)");
    }
}
