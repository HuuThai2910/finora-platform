package com.finora.user.service.impl;

import com.finora.common.exception.BusinessException;
import com.finora.user.config.KeycloakAdminProperties;
import com.finora.user.domain.UserRole;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.support.PiiMasker;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

/**
 * Triển khai dịch vụ tương tác với Keycloak Admin REST API — quản lý vòng đời tài khoản.
 * <p>
 * Sử dụng service account (client_credentials) để tạo user, gán role, đổi mật khẩu
 * và vô hiệu hóa tài khoản. Token endpoint được gọi trực tiếp cho các thao tác
 * xác thực người dùng (password grant, refresh).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KeycloakAdminServiceImpl implements KeycloakAdminService {

    private final KeycloakAdminProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();

    // ── Admin client (service account) ──────────────────────────────

    private Keycloak getKeycloakInstance() {
        return KeycloakBuilder.builder()
                .serverUrl(properties.getServerUrl())
                .realm(properties.getRealm())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .build();
    }

    private RealmResource getRealmResource() {
        return getKeycloakInstance().realm(properties.getRealm());
    }

    // ── Quản lý người dùng ──────────────────────────────────────────

    @Override
    public String createUser(String email, String password, String fullName, UserRole role) {
        UsersResource usersResource = getRealmResource().users();

        UserRepresentation user = new UserRepresentation();
        user.setEmail(email);
        user.setUsername(email);
        // Đăng ký không thu họ tên — tên chỉ có sau khi quét eKYC, nên có thể null
        user.setFirstName(fullName != null ? fullName : "");
        user.setLastName("");
        user.setEnabled(true);
        user.setEmailVerified(true);

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        user.setCredentials(Collections.singletonList(credential));

        try (Response response = usersResource.create(user)) {
            int status = response.getStatus();

            if (status == 409) {
                throw new BusinessException(HttpStatus.CONFLICT, "Email đã được sử dụng");
            }

            if (status != 201) {
                log.error("Keycloak tạo user thất bại — HTTP {}, email={}",
                        status, PiiMasker.maskEmail(email));
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Không thể tạo tài khoản trên hệ thống xác thực");
            }

            String locationHeader = response.getHeaderString("Location");
            String keycloakUserId = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);

            log.info("Đã tạo user trên Keycloak: keycloakUserId={}, email={}",
                    keycloakUserId, PiiMasker.maskEmail(email));

            assignRole(keycloakUserId, "ROLE_" + role.name());

            return keycloakUserId;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi khi tạo user trên Keycloak: email={}", PiiMasker.maskEmail(email), e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể tạo tài khoản trên hệ thống xác thực");
        }
    }

    @Override
    public AccessTokenResponse getUserToken(String email, String password) {
        String tokenUrl = properties.getServerUrl() + "/realms/" + properties.getRealm()
                + "/protocol/openid-connect/token";

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", OAuth2Constants.PASSWORD);
        params.add("client_id", properties.getClientId());
        params.add("client_secret", properties.getClientSecret());
        params.add("username", email);
        params.add("password", password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            ResponseEntity<AccessTokenResponse> response = restTemplate.exchange(
                    tokenUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(params, headers),
                    AccessTokenResponse.class);

            log.info("Xác thực Keycloak thành công cho email={}", PiiMasker.maskEmail(email));
            return response.getBody();

        } catch (HttpClientErrorException.Unauthorized e) {
            log.warn("Xác thực Keycloak thất bại (sai mật khẩu) cho email={}",
                    PiiMasker.maskEmail(email));
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng");
        } catch (HttpClientErrorException e) {
            log.error("Lỗi Keycloak token endpoint: status={}, email={}",
                    e.getStatusCode(), PiiMasker.maskEmail(email));
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Lỗi hệ thống xác thực, vui lòng thử lại sau");
        }
    }

    @Override
    public AccessTokenResponse refreshToken(String refreshToken) {
        String tokenUrl = properties.getServerUrl() + "/realms/" + properties.getRealm()
                + "/protocol/openid-connect/token";

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", OAuth2Constants.REFRESH_TOKEN);
        params.add("client_id", properties.getClientId());
        params.add("client_secret", properties.getClientSecret());
        params.add("refresh_token", refreshToken);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            ResponseEntity<AccessTokenResponse> response = restTemplate.exchange(
                    tokenUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(params, headers),
                    AccessTokenResponse.class);

            log.debug("Đã làm mới token thành công");
            return response.getBody();

        } catch (HttpClientErrorException e) {
            log.warn("Làm mới token thất bại: status={}", e.getStatusCode());
            throw new BusinessException(HttpStatus.UNAUTHORIZED,
                    "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại");
        }
    }

    @Override
    public void resetPassword(String keycloakUserId, String newPassword) {
        try {
            CredentialRepresentation credential = new CredentialRepresentation();
            credential.setType(CredentialRepresentation.PASSWORD);
            credential.setValue(newPassword);
            credential.setTemporary(false);

            getRealmResource().users().get(keycloakUserId).resetPassword(credential);
            log.info("Đã đặt lại mật khẩu trên Keycloak cho keycloakUserId={}", keycloakUserId);

        } catch (Exception e) {
            log.error("Lỗi đặt lại mật khẩu trên Keycloak: keycloakUserId={}", keycloakUserId, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể đổi mật khẩu, vui lòng thử lại sau");
        }
    }

    @Override
    public void disableUser(String keycloakUserId) {
        try {
            var userResource = getRealmResource().users().get(keycloakUserId);
            UserRepresentation user = userResource.toRepresentation();
            user.setEnabled(false);
            userResource.update(user);

            log.info("Đã vô hiệu hóa tài khoản Keycloak: keycloakUserId={}", keycloakUserId);

        } catch (Exception e) {
            log.error("Lỗi vô hiệu hóa tài khoản Keycloak: keycloakUserId={}", keycloakUserId, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể khóa tài khoản, vui lòng thử lại sau");
        }
    }

    @Override
    public void enableUser(String keycloakUserId) {
        try {
            var userResource = getRealmResource().users().get(keycloakUserId);
            UserRepresentation user = userResource.toRepresentation();
            user.setEnabled(true);
            userResource.update(user);

            log.info("Đã kích hoạt lại tài khoản Keycloak: keycloakUserId={}", keycloakUserId);

        } catch (Exception e) {
            log.error("Lỗi kích hoạt tài khoản Keycloak: keycloakUserId={}", keycloakUserId, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể mở khóa tài khoản, vui lòng thử lại sau");
        }
    }

    @Override
    public void assignRole(String keycloakUserId, String roleName) {
        try {
            RealmResource realmResource = getRealmResource();

            RoleRepresentation role = realmResource.roles().get(roleName).toRepresentation();
            realmResource.users().get(keycloakUserId)
                    .roles()
                    .realmLevel()
                    .add(List.of(role));

            log.info("Đã gán role {} cho keycloakUserId={}", roleName, keycloakUserId);

        } catch (jakarta.ws.rs.NotFoundException e) {
            log.error("Role {} không tồn tại trên Keycloak", roleName);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Vai trò không tồn tại trên hệ thống xác thực: " + roleName);
        } catch (Exception e) {
            log.error("Lỗi gán role {} cho keycloakUserId={}", roleName, keycloakUserId, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể gán vai trò, vui lòng thử lại sau");
        }
    }

    @Override
    public void revokeRefreshToken(String refreshToken) {
        String revokeUrl = properties.getServerUrl() + "/realms/" + properties.getRealm()
                + "/protocol/openid-connect/revoke";

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("client_id", properties.getClientId());
        params.add("client_secret", properties.getClientSecret());
        params.add("token", refreshToken);
        params.add("token_type_hint", "refresh_token");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            restTemplate.exchange(revokeUrl, HttpMethod.POST,
                    new HttpEntity<>(params, headers), Void.class);
            log.debug("Đã thu hồi refresh token thành công");
        } catch (Exception e) {
            log.warn("Không thể thu hồi refresh token: {}", e.getMessage());
        }
    }

    @Override
    public void deleteUser(String keycloakUserId) {
        try {
            getRealmResource().users().get(keycloakUserId).remove();
            log.info("Đã xóa user Keycloak (rollback): keycloakUserId={}", keycloakUserId);
        } catch (Exception e) {
            log.error("Không thể xóa user Keycloak khi rollback: keycloakUserId={}",
                    keycloakUserId, e);
        }
    }
}
