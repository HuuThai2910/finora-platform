package com.finora.loan.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;

/**
 * Lấy access token từ hai kênh mà client của FINORA đang dùng.
 *
 * <p>Mobile gửi token qua header {@code Authorization: Bearer}, còn web quản trị nhận
 * token trong cookie HttpOnly {@code access_token} do finora-user đặt. Header được ưu
 * tiên để khi một client gửi cả hai thì giá trị mới hơn thắng.</p>
 *
 * <p>Lớp này lặp lại cách giải quyết của finora-user thay vì dùng chung mã nguồn, vì
 * hai service thuộc hai owner khác nhau và {@code finora-common} chưa nhận trách nhiệm
 * về tầng bảo mật. Khi tên cookie đổi, cả hai nơi phải đổi cùng lúc.</p>
 */
@Component
public class DualBearerTokenResolver implements BearerTokenResolver {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ACCESS_TOKEN_COOKIE = "access_token";

    @Override
    public String resolve(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }

        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }

        // Không có token — Spring Security xử lý request như ẩn danh và chặn ở tầng phân quyền.
        return null;
    }
}
