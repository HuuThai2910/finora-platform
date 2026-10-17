package com.finora.notification.controller;

import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ratelimit.RateLimited;
import com.finora.notification.dto.request.RegisterPushDeviceRequest;
import com.finora.notification.dto.response.PushDeviceResponse;
import com.finora.notification.service.push.PushDeviceService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thiết bị nhận push của người đang đăng nhập (D62). App gọi đăng ký sau khi người dùng cấp quyền thông báo và mỗi lần
 * mở phiên (token có thể đổi); gọi gỡ trước khi xóa phiên lúc đăng xuất.
 */
@RestController
@RequestMapping("/api/v1/notifications/devices")
@RequiredArgsConstructor
public class PushDeviceController {

    private final PushDeviceService devices;

    /** Upsert theo token, idempotent. Giới hạn tần suất để một tài khoản không đổ token rác vào bảng. */
    @PostMapping
    @RateLimited(value = "notification.push-device", limit = 20, window = "PT10M")
    public PushDeviceResponse register(@Valid @RequestBody RegisterPushDeviceRequest request) {
        return devices.register(SecurityUtils.getCurrentUserId(), request.token(), request.platform(),
                request.appVersion());
    }

    /** Luôn 204: thiết bị không tồn tại hoặc của người khác không bị đụng tới và cũng không bị tiết lộ. */
    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> unregister(@PathVariable UUID deviceId) {
        devices.unregister(SecurityUtils.getCurrentUserId(), deviceId);
        return ResponseEntity.noContent().build();
    }
}
