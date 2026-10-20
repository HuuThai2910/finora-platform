package com.finora.payment.integration.payout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 (hex) trên nguyên body callback của provider payout (D43). So sánh thời gian hằng. */
public final class PayoutSignature {

    public static final String HEADER = "X-Payout-Signature";

    private PayoutSignature() {
    }

    public static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Không tạo được chữ ký callback payout", exception);
        }
    }

    public static boolean matches(String body, String secret, String supplied) {
        if (body == null || secret == null || secret.isBlank() || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(body, secret).getBytes(StandardCharsets.US_ASCII),
                supplied.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }
}
