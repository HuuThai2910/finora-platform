package com.finora.user.support;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpHeaders;

import java.util.Arrays;

/**
 * Tiện ích trích xuất thông tin từ HTTP request — IP, User-Agent, cookie, client type.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HttpRequestUtils {

    private static final String HEADER_CLIENT_TYPE = "X-Client-Type";
    private static final String CLIENT_TYPE_MOBILE = "mobile";
    private static final String COOKIE_REFRESH_TOKEN = "refresh_token";

    /**
     * Trích xuất IP address — hỗ trợ proxy/load balancer qua header X-Forwarded-For.
     */
    public static String extractIpAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Kiểm tra client type từ header {@code X-Client-Type}.
     */
    public static boolean isMobileClient(HttpServletRequest request) {
        return CLIENT_TYPE_MOBILE.equalsIgnoreCase(request.getHeader(HEADER_CLIENT_TYPE));
    }

    /**
     * Trích xuất User-Agent từ request header.
     */
    public static String extractUserAgent(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.USER_AGENT);
    }

    /**
     * Trích xuất refresh token: ưu tiên giá trị truyền vào → cookie.
     */
    public static String extractRefreshToken(String fromBody, HttpServletRequest request) {
        if (fromBody != null && !fromBody.isBlank()) {
            return fromBody;
        }

        if (request.getCookies() != null) {
            return Arrays.stream(request.getCookies())
                    .filter(c -> COOKIE_REFRESH_TOKEN.equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }
}
