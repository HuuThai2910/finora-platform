package com.finora.notification.integration.push;

import java.time.Duration;
import java.util.Map;

/**
 * Một push gửi tới một thiết bị. {@code title}/{@code body} đã che (không số tiền, tên, mã khoản vay); {@code data} chỉ
 * mang ID thông báo, loại và nhóm để app mở đúng tin rồi tự tải chi tiết bằng JWT (D63).
 *
 * @param tokenRef dấu vân tay ngắn của token, dùng thay token khi log
 */
public record PushMessage(long deliveryId, String token, String tokenRef, String title, String body,
        Map<String, String> data, Duration ttl) {

    /** Không in token ra log dù record bị log nhầm. */
    @Override
    public String toString() {
        return "PushMessage[deliveryId=" + deliveryId + ", tokenRef=" + tokenRef + "]";
    }
}
