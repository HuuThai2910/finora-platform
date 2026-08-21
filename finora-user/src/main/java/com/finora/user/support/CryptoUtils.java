package com.finora.user.support;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Tiện ích mã hóa — cung cấp HMAC-SHA256 (deterministic hash để lookup/unique check)
 * và AES-256-GCM (encrypt/decrypt PII).
 * <p>
 * Không dùng thư viện crypto bên ngoài, chỉ dùng javax.crypto + java.security.
 */
public final class CryptoUtils {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String AES_ALGORITHM = "AES";
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;          // 96-bit IV theo khuyến nghị NIST
    private static final int GCM_TAG_LENGTH_BITS = 128;   // 128-bit authentication tag

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CryptoUtils() {
        // Lớp tiện ích — không cho phép khởi tạo instance
    }

    /**
     * Tạo HMAC-SHA256 từ data + secret, trả về chuỗi hex 64 ký tự.
     * Dùng cho lookup/unique check (deterministic), KHÔNG dùng để mã hóa dữ liệu.
     */
    public static String hmacSha256(String data, String secret) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Lỗi tính HMAC-SHA256", e);
        }
    }

    /**
     * Mã hóa plaintext bằng AES-256-GCM. Trả về Base64(IV + ciphertext).
     * Mỗi lần gọi tạo IV ngẫu nhiên nên kết quả không deterministic — phù hợp lưu trữ PII.
     */
    public static String encryptAesGcm(String plaintext, String secret) {
        try {
            byte[] key = deriveKey(secret);
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, AES_ALGORITHM),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] ciphertextBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Ghép IV + ciphertext rồi encode Base64
            byte[] combined = new byte[iv.length + ciphertextBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertextBytes, 0, combined, iv.length, ciphertextBytes.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Lỗi mã hóa AES-GCM", e);
        }
    }

    /**
     * Giải mã ciphertext (Base64) bằng AES-256-GCM. Tách IV từ đầu chuỗi byte.
     */
    public static String decryptAesGcm(String ciphertext, String secret) {
        try {
            byte[] key = deriveKey(secret);
            byte[] combined = Base64.getDecoder().decode(ciphertext);

            // Tách IV (12 byte đầu) và phần ciphertext còn lại
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] encryptedBytes = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, AES_ALGORITHM),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] plaintextBytes = cipher.doFinal(encryptedBytes);
            return new String(plaintextBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Lỗi giải mã AES-GCM", e);
        }
    }

    /**
     * Derive AES-256 key: dùng SHA-256 hash secret rồi lấy 32 byte đầu.
     * Đảm bảo key luôn đúng 256 bit bất kể độ dài secret đầu vào.
     */
    private static byte[] deriveKey(String secret) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(secret.getBytes(StandardCharsets.UTF_8));
    }
}
