package com.finora.notification.service.push;

import com.finora.notification.domain.enums.DevicePlatform;
import com.finora.notification.dto.response.PushDeviceResponse;

import java.util.UUID;

/**
 * Vòng đời thiết bị nhận push (D62): đăng ký/làm mới, gỡ khi đăng xuất, giới hạn số thiết bị.
 */
public interface PushDeviceService {

    /**
     * Đăng ký là upsert theo token, chạy được nhiều lần (mỗi lần mở app) mà không tạo bản ghi thừa; token sai định dạng
     * Expo bị từ chối với {@code PUSH_TOKEN_INVALID}.
     */
    PushDeviceResponse register(String userId, String token, DevicePlatform platform, String appVersion);

    /** Đăng xuất: chỉ gỡ thiết bị của chính người gọi; ID lạ hoặc của người khác không làm gì (không lộ sự tồn tại). */
    void unregister(String userId, UUID deviceId);
}
