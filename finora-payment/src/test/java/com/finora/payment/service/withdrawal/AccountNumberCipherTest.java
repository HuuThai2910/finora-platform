package com.finora.payment.service.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** D44: số tài khoản chỉ lưu dạng AES-GCM và dạng che; hash idempotency dùng HMAC không dò ngược được. */
class AccountNumberCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String OTHER_KEY = Base64.getEncoder()
            .encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    private final AccountNumberCipher cipher = new AccountNumberCipher(KEY);

    @Test
    void encryptsWithAFreshIvAndDecryptsBack() {
        String first = cipher.encrypt("0123456789");
        String second = cipher.encrypt("0123456789");

        assertThat(first).startsWith("v1:").doesNotContain("0123456789").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("0123456789");
        assertThat(cipher.decrypt(second)).isEqualTo("0123456789");
    }

    @Test
    void tamperedCiphertextOrAnotherKeyIsRejected() {
        String stored = cipher.encrypt("0123456789");
        byte[] bytes = Base64.getDecoder().decode(stored.substring(3));
        bytes[bytes.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AccountNumberCipher(OTHER_KEY).decrypt(stored))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fingerprintIsStablePerKeyAndHidesTheNumber() {
        String fingerprint = cipher.fingerprint("0123456789");

        assertThat(fingerprint).hasSize(64).isEqualTo(cipher.fingerprint("0123456789"))
                .isNotEqualTo(cipher.fingerprint("0123456780"))
                .isNotEqualTo(new AccountNumberCipher(OTHER_KEY).fingerprint("0123456789"));
    }

    @Test
    void maskKeepsOnlyTheLastFourDigits() {
        assertThat(AccountNumberCipher.mask("0123456789")).isEqualTo("******6789");
    }

    @Test
    void keyMustBeThirtyTwoBytesOfBase64() {
        assertThatThrownBy(() -> new AccountNumberCipher(Base64.getEncoder().encodeToString("short".getBytes())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AccountNumberCipher("not base64 !"))
                .isInstanceOf(IllegalStateException.class);
    }
}
