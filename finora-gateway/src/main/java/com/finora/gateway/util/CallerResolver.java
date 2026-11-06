package com.finora.gateway.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Đọc token và người gọi của một request. Token lấy như các service ({@code DualBearerTokenResolver}): header
 * {@code Authorization: Bearer} của mobile trước, rồi cookie HttpOnly {@code access_token} của web.
 */
@Component
@RequiredArgsConstructor
public class CallerResolver {

    static final String ACCESS_TOKEN_COOKIE = "access_token";
    private static final String BEARER = "Bearer ";
    /** Vai trò hiển thị theo thứ tự ưu tiên khi token có nhiều realm role. */
    private static final List<String> DISPLAY_ROLES = List.of("ADMIN", "STAFF", "BORROWER", "INVESTOR", "USER");

    private final ObjectMapper objectMapper;

    static String token(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            String value = header.substring(BEARER.length()).trim();
            if (!value.isEmpty()) return value;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (ACCESS_TOKEN_COOKIE.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Tên và vai trò đọc từ phần payload của JWT, KHÔNG kiểm chữ ký: chỉ để ghi vào access log. Token giả chỉ làm
     * sai nhãn của chính lượt gọi đó, còn request vẫn bị service phía sau từ chối.
     */
    public Caller resolve(HttpServletRequest request) {
        String token = token(request);
        if (token == null) return Caller.ANONYMOUS;
        String[] parts = token.split("\\.");
        if (parts.length < 2) return Caller.ANONYMOUS;
        try {
            JsonNode claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            String name = firstText(claims, "preferred_username", "email", "sub");
            return new Caller(name, displayRole(claims.path("realm_access").path("roles")));
        } catch (Exception malformed) {
            return Caller.ANONYMOUS;
        }
    }

    static String displayRole(JsonNode roles) {
        if (!roles.isArray()) return null;
        for (String candidate : DISPLAY_ROLES) {
            for (JsonNode role : roles) {
                String name = role.asText().toUpperCase(Locale.ROOT);
                if (name.equals(candidate) || name.equals("ROLE_" + candidate)) return candidate;
            }
        }
        return null;
    }

    private static String firstText(JsonNode claims, String... names) {
        for (String name : names) {
            String value = claims.path(name).asText(null);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    public record Caller(String name, String role) {
        static final Caller ANONYMOUS = new Caller(null, null);
    }
}
