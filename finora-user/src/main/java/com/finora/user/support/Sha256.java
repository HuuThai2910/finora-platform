package com.finora.user.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 dạng hex chữ thường (64 ký tự) — dùng cho bằng chứng eKYC và hash nội dung điều khoản. */
public final class Sha256 {

    private Sha256() {
    }

    public static String hex(byte[] content) {
        return HexFormat.of().formatHex(digest().digest(content));
    }

    public static String hex(String text) {
        return hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Băm nhiều phần liên tiếp như một luồng (ví dụ chuỗi ảnh tĩnh theo đúng thứ tự gửi). */
    public static String hex(Iterable<byte[]> parts) {
        MessageDigest digest = digest();
        for (byte[] part : parts) {
            digest.update(part);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM thiếu SHA-256", e);
        }
    }
}
