package com.finora.user.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;

/**
 * Hỗ trợ dual-mode token delivery:
 * 1. Mobile gửi token qua header Authorization: Bearer ...
 * 2. Web gửi token qua HttpOnly Cookie "access_token" (SameSite=Lax)
 *
 * Ưu tiên header trước — nếu client gửi cả hai thì header thắng,
 * tránh xung đột khi web app chuyển sang dùng header.
 */
@Slf4j
@Component
public class DualBearerTokenResolver implements BearerTokenResolver {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ACCESS_TOKEN_COOKIE = "access_token";

    @Override
    public String resolve(HttpServletRequest request) {
        // Bước 1: Kiểm tra header Authorization (mobile / SPA dùng header)
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }

        // Bước 2: Fallback sang cookie access_token (web dùng HttpOnly cookie)
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        }

        // Không tìm thấy token — Spring Security sẽ coi request là anonymous
        return null;
    }
}
