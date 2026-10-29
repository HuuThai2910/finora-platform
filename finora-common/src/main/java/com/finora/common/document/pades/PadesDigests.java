package com.finora.common.document.pades;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 dạng bytes cho CMS (message-digest, ESSCertIDv2); hex chữ thường dùng để lưu và đối chiếu. */
final class PadesDigests {

    private PadesDigests() {
    }

    static byte[] sha256(byte[] value) {
        return newDigest().digest(value);
    }

    static byte[] sha256(InputStream input) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    static byte[] sha256(byte[] value, int[] byteRange) {
        MessageDigest digest = newDigest();
        digest.update(value, byteRange[0], byteRange[1]);
        digest.update(value, byteRange[2], byteRange[3]);
        return digest.digest();
    }

    static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    static String sha256Hex(byte[] value) {
        return hex(sha256(value));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", exception);
        }
    }
}
