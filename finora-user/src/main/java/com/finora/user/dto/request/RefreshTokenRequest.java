package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Yêu cầu làm mới access token — chỉ dùng cho mobile client.
 * <p>
 * Web client gửi refresh token qua HTTP-only Cookie nên không cần DTO này.
 */
public record RefreshTokenRequest(

        @NotBlank
        String refreshToken
) {
}
