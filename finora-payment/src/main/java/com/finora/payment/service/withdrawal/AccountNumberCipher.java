package com.finora.payment.service.withdrawal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mã hóa số tài khoản ngân hàng nhận tiền bằng AES-256-GCM (D44, LEGAL-DATA-01). Dạng lưu {@code v1:<base64(iv|ct|tag)>};
 * IV ngẫu nhiên mỗi lần nên cùng số tài khoản cho bản mã khác nhau. API và log chỉ dùng {@link #mask}.
 */
public class AccountNumberCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecretKey fingerprintKey;
    private final SecureRandom random = new SecureRandom();

    public AccountNumberCipher(String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("finora.payment.withdrawal.account-encryption-key phải là base64", exception);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("finora.payment.withdrawal.account-encryption-key phải dài đúng 32 byte");
        }
        this.key = new SecretKeySpec(bytes, "AES");
        // Khóa riêng cho dấu vân tay, dẫn xuất từ khóa AES: không dùng một khóa cho hai thuật toán.
        this.fingerprintKey = new SecretKeySpec(hmac(new SecretKeySpec(bytes, "HmacSHA256"),
                "finora-account-number-fingerprint-v1"), "HmacSHA256");
    }

    /**
     * HMAC-SHA256 (hex) của số tài khoản, dùng trong hash idempotency thay cho số thật: số tài khoản chỉ 6–19 chữ số nên
     * SHA-256 thuần dò ngược được, còn HMAC thì cần khóa.
     */
    public String fingerprint(String accountNumber) {
        return java.util.HexFormat.of().formatHex(hmac(fingerprintKey, accountNumber));
    }

    private static byte[] hmac(SecretKey secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secret);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Không tạo được dấu vân tay số tài khoản", exception);
        }
    }

    public String encrypt(String accountNumber) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(accountNumber.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Không mã hóa được số tài khoản", exception);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Bản mã số tài khoản không đúng định dạng");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Không giải mã được số tài khoản (sai khóa hoặc dữ liệu bị sửa)", exception);
        }
    }

    /** Chỉ giữ 4 số cuối: {@code ******1234}. */
    public static String mask(String accountNumber) {
        String digits = accountNumber == null ? "" : accountNumber;
        String last = digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
        return "******" + last;
    }
}
