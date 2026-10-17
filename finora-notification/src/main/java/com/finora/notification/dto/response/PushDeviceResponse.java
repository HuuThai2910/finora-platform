package com.finora.notification.dto.response;

import com.finora.notification.domain.DevicePlatform;
import com.finora.notification.domain.PushDevice;
import com.finora.notification.domain.PushDeviceStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Kết quả đăng ký thiết bị. App giữ {@code deviceId} để gỡ khi đăng xuất; {@code tokenRef} là vân tay ngắn để hỗ trợ đối
 * chiếu, không trả lại token.
 */
public record PushDeviceResponse(UUID deviceId, PushDeviceStatus status, DevicePlatform platform, String tokenRef,
        Instant lastRegisteredAt) {
    public static PushDeviceResponse from(PushDevice device) {
        return new PushDeviceResponse(device.getId(), device.getStatus(), device.getPlatform(), device.tokenRef(),
                device.getLastRegisteredAt());
    }
}
