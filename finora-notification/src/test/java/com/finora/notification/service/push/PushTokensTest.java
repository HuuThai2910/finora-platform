package com.finora.notification.service.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.integration.push.PushMessage;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Token Expo chỉ đi vào DB và request tới Expo; mọi chỗ in ra chỉ dùng vân tay (D62). */
class PushTokensTest {

    private static final String TOKEN = "ExponentPushToken[FAKE-test-token-0001]";

    @Test
    void acceptsBothExpoTokenPrefixesAndRejectsOtherStrings() {
        assertThat(PushTokens.isValid(TOKEN)).isTrue();
        assertThat(PushTokens.isValid("ExpoPushToken[abcdefgh12345678]")).isTrue();
        assertThat(PushTokens.isValid("fcm:abc")).isFalse();
        assertThat(PushTokens.isValid("ExponentPushToken[short]")).isFalse();
        assertThat(PushTokens.isValid("ExponentPushToken[abc def ghi jkl]")).isFalse();
        assertThat(PushTokens.isValid(null)).isFalse();
    }

    @Test
    void hashIsStableSha256HexWithoutTheToken() {
        String hash = PushTokens.hash(TOKEN);

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(PushTokens.hash(TOKEN)).doesNotContain("FAKE-test");
    }

    @Test
    void pushMessageNeverPrintsTheToken() {
        PushMessage message = new PushMessage(7L, TOKEN, "abc123def456", "Tiêu đề", "Nội dung", Map.of(),
                Duration.ofHours(1));

        assertThat(message.toString()).doesNotContain("ExponentPushToken").doesNotContain("FAKE-test").contains("abc123def456");
    }

    @Test
    void expoAccessTokenIsMaskedWhenPropertiesArePrinted() {
        NotificationProperties.Expo expo = new NotificationProperties.Expo(null, "secret-access-token", null, null);

        assertThat(expo.toString()).doesNotContain("secret-access-token").contains("***");
    }
}
