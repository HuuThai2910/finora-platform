package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Yêu cầu làm mới access token — chỉ dùng cho mobile client.
 * <p>
 * Web client gửi refresh token qua HTTP-only Cookie nên không cần DTO này.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenRequest {

    @NotBlank
    private String refreshToken;
}
