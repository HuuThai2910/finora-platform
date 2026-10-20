package com.finora.notification.service.push;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Quy ước Expo push token: {@code ExponentPushToken[...]} (hoặc {@code ExpoPushToken[...]}). Token chỉ đi vào database và
 * request tới Expo; log và API chỉ dùng hash (vân tay), không bao giờ token đầy đủ.
 */
public final class PushTokens {

    /** Phần trong ngoặc của Expo là chuỗi base64url-like; giới hạn độ dài để chặn dữ liệu rác. */
    public static final Pattern EXPO_TOKEN = Pattern.compile("^Expo(nent)?PushToken\\[[A-Za-z0-9_-]{8,200}]$");

    private PushTokens() {
    }

    public static boolean isValid(String token) {
        return token != null && EXPO_TOKEN.matcher(token).matches();
    }

    /** SHA-256 hex (64 ký tự) của token, dùng để đối chiếu mà không lưu/log token. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM thiếu SHA-256", exception);
        }
    }
}
