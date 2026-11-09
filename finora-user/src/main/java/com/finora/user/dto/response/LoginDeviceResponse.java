package com.finora.user.dto.response;

import java.time.Instant;

/**
 * Một thiết bị đã đăng nhập vào tài khoản. Không có mã thiết bị: client nhận ra máy hiện tại qua {@code current}.
 *
 * @param id             khóa dòng, dùng cho thao tác "Đăng xuất thiết bị"
 * @param deviceName     tên hiển thị client tự khai hoặc suy từ trình duyệt
 * @param osName         hệ điều hành và phiên bản, {@code null} khi không biết
 * @param clientType     {@code MOBILE} hoặc {@code WEB}
 * @param ipAddress      IP lần đăng nhập cuối: đã che khi người dùng tự xem, đầy đủ với nhân viên hỗ trợ
 * @param current        thiết bị của chính request đang gọi
 * @param canSignOut     còn phiên đăng nhập để đăng xuất từ xa (không áp dụng cho thiết bị hiện tại)
 */
public record LoginDeviceResponse(
        Long id,
        String deviceName,
        String osName,
        String clientType,
        String appVersion,
        String ipAddress,
        Instant firstLoginAt,
        Instant lastLoginAt,
        int loginCount,
        boolean current,
        boolean canSignOut
) {
}
