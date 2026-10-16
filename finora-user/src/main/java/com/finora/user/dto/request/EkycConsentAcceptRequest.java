package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Người dùng đồng ý điều khoản eKYC đúng nội dung đã đọc.
 * <p>
 * Client gửi lại {@code version} và {@code textSha256} nhận từ {@code GET …/ekyc/consent}; nếu server đã
 * đổi điều khoản trong lúc người dùng đọc thì hai giá trị lệch và request bị từ chối
 * {@code 409 CONSENT_VERSION_STALE} — không ghi nhận đồng ý cho nội dung người dùng chưa thấy.
 */
public record EkycConsentAcceptRequest(

        @NotBlank
        @Size(max = 40)
        String version,

        @NotBlank
        @Pattern(regexp = "[0-9a-fA-F]{64}")
        String textSha256,

        @Pattern(regexp = "(?i)ios|android|web")
        String platform,

        @Size(max = 40)
        String appVersion
) {
}
