package com.finora.user.support;

import com.finora.user.config.AuthCookieProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Quản lý token cookie cho web client — tạo và xóa HttpOnly cookie.
 */
@Component
@RequiredArgsConstructor
public class AuthCookieManager {

    /** Thời gian sống access token cookie — 5 phút */
    private static final long ACCESS_TOKEN_MAX_AGE_SECONDS = 300;

    /** Thời gian sống refresh token cookie — 7 ngày */
    private static final long REFRESH_TOKEN_MAX_AGE_SECONDS = 604_800;

    private static final String COOKIE_ACCESS_TOKEN = "access_token";
    private static final String COOKIE_REFRESH_TOKEN = "refresh_token";

    private final AuthCookieProperties cookieProperties;

    /**
     * Đặt access_token và refresh_token vào HttpOnly Cookie.
     * SameSite=Lax để chống CSRF; Secure lấy từ cấu hình (false ở local).
     */
    public void addTokenCookies(HttpServletResponse response,
                                 String accessToken, String refreshToken) {
        ResponseCookie accessCookie = ResponseCookie.from(COOKIE_ACCESS_TOKEN, accessToken)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .domain(cookieProperties.domain())
                .path("/")
                .maxAge(Duration.ofSeconds(ACCESS_TOKEN_MAX_AGE_SECONDS))
                .sameSite("Lax")
                .build();

        ResponseCookie refreshCookie = ResponseCookie.from(COOKIE_REFRESH_TOKEN, refreshToken)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .domain(cookieProperties.domain())
                .path("/api/v1/auth")
                .maxAge(Duration.ofSeconds(REFRESH_TOKEN_MAX_AGE_SECONDS))
                .sameSite("Lax")
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, accessCookie.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
    }

    /**
     * Xóa cookie token — đặt Max-Age=0 để trình duyệt xóa ngay.
     */
    public void clearTokenCookies(HttpServletResponse response) {
        ResponseCookie clearAccess = ResponseCookie.from(COOKIE_ACCESS_TOKEN, "")
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .domain(cookieProperties.domain())
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();

        ResponseCookie clearRefresh = ResponseCookie.from(COOKIE_REFRESH_TOKEN, "")
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .domain(cookieProperties.domain())
                .path("/api/v1/auth")
                .maxAge(0)
                .sameSite("Lax")
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, clearAccess.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefresh.toString());
    }
}
