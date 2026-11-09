package com.finora.user.domain.model;

import com.finora.user.domain.enums.LoginClientType;

import java.util.Objects;

/**
 * Thiết bị của một lượt đăng nhập, đọc từ header của request (đã kiểm độ dài và ký tự). Mọi trường do client tự khai
 * nên chỉ dùng để hiển thị, không dùng cho quyết định bảo mật nào.
 *
 * @param deviceId   mã thiết bị app/web tự sinh ({@code X-Device-Id}), hoặc suy từ User-Agent khi thiếu header
 * @param deviceName tên hiển thị, ví dụ "iPhone 15 Pro" hoặc "Chrome trên Windows"
 * @param osName     hệ điều hành và phiên bản, {@code null} khi không biết
 * @param clientType app di động hay trình duyệt
 * @param appVersion phiên bản app, {@code null} với web hoặc khi không gửi
 * @param ipAddress  IP thật của người dùng (gateway đặt {@code X-Forwarded-For})
 */
public record LoginDeviceContext(
        String deviceId,
        String deviceName,
        String osName,
        LoginClientType clientType,
        String appVersion,
        String ipAddress
) {

    public LoginDeviceContext {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(deviceName, "deviceName");
        Objects.requireNonNull(clientType, "clientType");
    }
}
