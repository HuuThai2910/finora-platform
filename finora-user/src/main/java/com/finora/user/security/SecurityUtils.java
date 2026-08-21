package com.finora.user.security;

import com.finora.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tiện ích trích thông tin người dùng hiện tại từ SecurityContext.
 * Chỉ dùng trong request đã xác thực — gọi ngoài context sẽ ném BusinessException.
 */
public final class SecurityUtils {

    private SecurityUtils() {
        // Lớp tiện ích — không cho phép khởi tạo instance
    }

    /**
     * Lấy Keycloak user ID (UUID) từ claim "sub" trong JWT.
     * Đây là định danh duy nhất của user trong hệ thống Keycloak.
     */
    public static UUID getCurrentKeycloakUserId() {
        return UUID.fromString(getJwt().getSubject());
    }

    /**
     * Lấy email từ claim "email" trong JWT.
     */
    public static String getCurrentEmail() {
        return getJwt().getClaimAsString("email");
    }

    /**
     * Lấy tập hợp tất cả roles/authorities đã được gán cho user hiện tại.
     * Bao gồm cả realm roles (ROLE_BORROWER, ...) và resource roles (user:profile:read, ...).
     */
    public static Set<String> getCurrentRoles() {
        return getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    /**
     * Kiểm tra user hiện tại có role cụ thể hay không.
     * Hỗ trợ cả format có prefix "ROLE_" (realm role) và không prefix (resource role).
     */
    public static boolean hasRole(String role) {
        return getCurrentRoles().contains(role);
    }

    /**
     * Lấy Authentication từ SecurityContext — ném exception nếu chưa xác thực.
     */
    private static Authentication getAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED",
                    "Yêu cầu xác thực để truy cập tài nguyên này");
        }
        return authentication;
    }

    /**
     * Trích JWT từ Authentication — ném exception nếu principal không phải Jwt.
     */
    private static Jwt getJwt() {
        Authentication authentication = getAuthentication();
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED,
                "INVALID_TOKEN",
                "Token xác thực không hợp lệ hoặc không phải JWT");
    }
}
