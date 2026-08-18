package com.finora.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Phản hồi xác thực — trả về sau login, register hoặc refresh token.
 * <p>
 * {@code accessToken} và {@code refreshToken} chỉ có giá trị cho mobile client.
 * Web client nhận token qua HTTP-only Cookie.
 */
public record AuthResponse(

        Long userId,
        String email,
        String fullName,
        List<String> roles,

        /** Access token JWT — chỉ trả cho mobile client */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String accessToken,

        /** Refresh token — chỉ trả cho mobile client */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String refreshToken
) {
}
