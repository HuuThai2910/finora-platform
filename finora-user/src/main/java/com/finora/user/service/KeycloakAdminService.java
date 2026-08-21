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

    void resetPassword(String keycloakUserId, String newPassword);

    void disableUser(String keycloakUserId);

    void enableUser(String keycloakUserId);

    void assignRole(String keycloakUserId, String roleName);

    void revokeRefreshToken(String refreshToken);

    void deleteUser(String keycloakUserId);
}
