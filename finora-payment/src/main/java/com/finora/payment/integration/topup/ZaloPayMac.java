package com.finora.payment.integration.topup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ZaloPayMac {
    private ZaloPayMac() {}

    public static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Không thể tạo chữ ký ZaloPay", exception);
        }
    }

    public static boolean matches(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(
                expected.toLowerCase().getBytes(StandardCharsets.US_ASCII),
                supplied.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }
}
