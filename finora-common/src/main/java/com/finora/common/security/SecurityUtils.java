package com.finora.common.security;

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
 * Tiện ích truy xuất thông tin xác thực và danh tính người dùng hiện tại từ SecurityContext / JWT.
 */
public final class SecurityUtils {

    public static final String USER_ID_CLAIM = "user_id";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private SecurityUtils() {
    }

    /**
     * Lấy user_id nội bộ từ claim "user_id" trong JWT. Nếu không có claim "user_id", fallback về "sub".
     * Ném BusinessException UNAUTHORIZED nếu chưa xác thực hoặc không tìm thấy ID.
     */
    public static String getCurrentUserId() {
        Jwt jwt = getJwt();
        String userId = jwt.getClaimAsString(USER_ID_CLAIM);
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        String subject = jwt.getSubject();
        if (subject != null && !subject.isBlank()) {
            return subject;
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED,
                "MISSING_USER_ID_CLAIM",
                "Phiên đăng nhập thiếu thông tin định danh, vui lòng đăng nhập lại");
    }

    /**
     * Lấy Keycloak User ID (UUID) từ claim "sub".
     */
    public static UUID getCurrentKeycloakUserId() {
        try {
            return UUID.fromString(getJwt().getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED,
                    "INVALID_USER_ID",
                    "Định danh Keycloak (UUID) không hợp lệ");
        }
    }

    /**
     * Lấy email từ claim "email" trong JWT.
     */
    public static String getCurrentEmail() {
        return getJwt().getClaimAsString("email");
    }

    /**
     * Lấy username từ claim "preferred_username" trong JWT.
     */
    public static String getCurrentUsername() {
        return getJwt().getClaimAsString("preferred_username");
    }

    /**
     * Lấy tập hợp tất cả roles/authorities của user hiện tại.
     */
    public static Set<String> getCurrentRoles() {
        return getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    /**
     * Kiểm tra user hiện tại có role chỉ định hay không.
     */
    public static boolean hasRole(String role) {
        if (role == null) {
            return false;
        }
        return getCurrentRoles().contains(role);
    }

    /**
     * Kiểm tra user hiện tại có quyền ROLE_ADMIN hay không.
     */
    public static boolean isAdmin() {
        return hasRole(ROLE_ADMIN);
    }

    /**
     * Yêu cầu quyền ROLE_ADMIN, ném FORBIDDEN nếu không phải.
     */
    public static void requireAdmin() {
        if (!isAdmin()) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "ADMIN_ROLE_REQUIRED",
                    "Tài khoản không có quyền quản trị để thực hiện thao tác này");
        }
    }

    /**
     * Lấy Authentication từ SecurityContext.
     */
    public static Authentication getAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED",
                    "Yêu cầu xác thực để truy cập tài nguyên này");
        }
        return authentication;
    }

    /**
     * Lấy JWT token từ Authentication.
     */
    public static Jwt getJwt() {
        Authentication authentication = getAuthentication();
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED,
                "INVALID_TOKEN",
                "Token xác thực không hợp lệ hoặc không phải JWT");
    }
}
