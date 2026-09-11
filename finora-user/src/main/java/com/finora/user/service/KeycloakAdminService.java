package com.finora.user.service;

import com.finora.user.domain.UserRole;
import org.keycloak.representations.AccessTokenResponse;

/**
 * Interface dịch vụ tương tác với Keycloak Admin REST API — quản lý vòng đời tài khoản.
 */
public interface KeycloakAdminService {

    String createUser(String email, String password, String fullName, UserRole role);

    AccessTokenResponse getUserToken(String email, String password);

    AccessTokenResponse refreshToken(String refreshToken);

    /**
     * Ghi ID hồ sơ nội bộ lên user Keycloak dưới dạng attribute {@code user_id}.
     * <p>
     * Protocol mapper {@code user-id} của client đọc attribute này và phát thành
     * claim {@code user_id} trong access token, nhờ đó các service khác biết
     * request thuộc về người dùng nào mà không phải gọi ngược finora-user.
     * <p>
     * Phải gọi sau khi hồ sơ đã được lưu (đã có ID) và trước khi cấp token đầu
     * tiên, nếu không token của phiên đăng ký sẽ thiếu claim.
     */
    void setUserIdAttribute(String keycloakUserId, Long userId);

    void resetPassword(String keycloakUserId, String newPassword);

    void disableUser(String keycloakUserId);

    void enableUser(String keycloakUserId);

    /**
     * Kiểm tra tài khoản Keycloak còn hoạt động hay đã bị khóa.
     *
     * @return {@code true} nếu tài khoản đang bật; {@code null} khi không tra cứu
     *         được (Keycloak lỗi/không tồn tại) để phía gọi phân biệt với "đã khóa"
     */
    Boolean isUserEnabled(String keycloakUserId);

    void assignRole(String keycloakUserId, String roleName);

    void revokeRefreshToken(String refreshToken);

    void deleteUser(String keycloakUserId);
}
