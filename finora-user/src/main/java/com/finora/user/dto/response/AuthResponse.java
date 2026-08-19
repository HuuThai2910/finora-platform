package com.finora.user.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Phản hồi xác thực — trả về sau login, register hoặc refresh token.
 * <p>
 * {@code accessToken} và {@code refreshToken} chỉ có giá trị cho mobile client.
 * Web client nhận token qua HTTP-only Cookie.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private Long userId;
    private String email;
    private String fullName;
    private List<String> roles;

    /** Access token JWT — chỉ trả cho mobile client */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String accessToken;

    /** Refresh token — chỉ trả cho mobile client */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String refreshToken;
}
