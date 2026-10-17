package com.finora.notification.dto.request;

import com.finora.notification.domain.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Đăng ký/làm mới Expo push token của thiết bị đang đăng nhập. Người sở hữu lấy từ JWT, không nhận từ body.
 * Thông điệp lỗi không lặp lại giá trị token.
 *
 * @param token      {@code ExponentPushToken[...]} do {@code expo-notifications} cấp
 * @param appVersion phiên bản app (tùy chọn) để vận hành biết bản nào đang nhận push
 */
public record RegisterPushDeviceRequest(
        @NotBlank(message = "Thiếu push token")
        @Size(max = 255, message = "Push token quá dài")
        @Pattern(regexp = "^Expo(nent)?PushToken\\[[A-Za-z0-9_-]{8,200}]$",
                message = "Push token không đúng định dạng Expo")
        String token,
        @NotNull(message = "Thiếu hệ điều hành")
        DevicePlatform platform,
        @Size(max = 40, message = "Phiên bản app quá dài")
        @Pattern(regexp = "^[0-9A-Za-z.+_-]*$", message = "Phiên bản app chứa ký tự không hợp lệ")
        String appVersion
) {
}
