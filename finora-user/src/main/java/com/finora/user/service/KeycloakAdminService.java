package com.finora.user.service;

import com.finora.common.exception.BusinessException;
import com.finora.user.config.KeycloakAdminProperties;
import com.finora.user.domain.UserRole;
import com.finora.user.util.PiiMasker;
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
 * Dịch vụ tương tác với Keycloak Admin REST API — quản lý vòng đời tài khoản.
 * <p>
 * Sử dụng service account (client_credentials) để tạo user, gán role, đổi mật khẩu
 * và vô hiệu hóa tài khoản. Token endpoint được gọi trực tiếp cho các thao tác
 * xác thực người dùng (password grant, refresh).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KeycloakAdminService {

    private final KeycloakAdminProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();

    // ── Admin client (service account) ──────────────────────────────

    /**
     * Tạo Keycloak admin instance bằng service account (client_credentials grant).
     * Mỗi lần gọi tạo instance mới để tránh vấn đề token hết hạn.
     */
    private Keycloak getKeycloakInstance() {
        return KeycloakBuilder.builder()
                .serverUrl(properties.serverUrl())
                .realm(properties.realm())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .build();
    }

    /**
     * Lấy RealmResource từ admin instance.
     */
    private RealmResource getRealmResource() {
        return getKeycloakInstance().realm(properties.realm());
    }

    // ── Quản lý người dùng ──────────────────────────────────────────

    /**
     * Tạo người dùng mới trên Keycloak — trả về Keycloak user ID.
     * <p>
     * Luồng: tạo user → gán password → gán realm role.
     * Nếu email đã tồn tại trên Keycloak (409 Conflict), ném BusinessException.
     *
     * @return Keycloak user ID (UUID dạng String)
     */
    public String createUser(String email, String password, String fullName, UserRole role) {
        UsersResource usersResource = getRealmResource().users();

        // Tạo UserRepresentation
        UserRepresentation user = new UserRepresentation();
        user.setEmail(email);
        user.setUsername(email);
        user.setFirstName(fullName);
        user.setLastName("");
        user.setEnabled(true);
        user.setEmailVerified(true);

        // Gán mật khẩu — không yêu cầu đổi lần đầu
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

            // Trích xuất user ID từ Location header
            String locationHeader = response.getHeaderString("Location");
            String keycloakUserId = locationHeader.substring(locationHeader.lastIndexOf('/') + 1);

            log.info("Đã tạo user trên Keycloak: keycloakUserId={}, email={}",
                    keycloakUserId, PiiMasker.maskEmail(email));

            // Gán realm role tương ứng (vd: ROLE_BORROWER)
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

    /**
     * Xác thực người dùng bằng email/password — trả về access token + refresh token.
     * <p>
     * Sử dụng Resource Owner Password Credentials grant (direct access grant)
     * qua token endpoint của Keycloak.
     */
    public AccessTokenResponse getUserToken(String email, String password) {
        String tokenUrl = properties.serverUrl() + "/realms/" + properties.realm()
                + "/protocol/openid-connect/token";

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", OAuth2Constants.PASSWORD);
        params.add("client_id", properties.clientId());
        params.add("client_secret", properties.clientSecret());
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

    /**
     * Làm mới access token bằng refresh token — gọi token endpoint với grant_type=refresh_token.
     */
    public AccessTokenResponse refreshToken(String refreshToken) {
        String tokenUrl = properties.serverUrl() + "/realms/" + properties.realm()
                + "/protocol/openid-connect/token";

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", OAuth2Constants.REFRESH_TOKEN);
        params.add("client_id", properties.clientId());
        params.add("client_secret", properties.clientSecret());
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

    /**
     * Đặt lại mật khẩu cho người dùng trên Keycloak.
     */
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

    /**
     * Vô hiệu hóa tài khoản Keycloak — người dùng không thể đăng nhập cho đến khi mở khóa.
     */
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

    /**
     * Kích hoạt lại tài khoản Keycloak đã bị vô hiệu hóa.
     */
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

    /**
     * Gán realm role cho người dùng Keycloak (ví dụ: ROLE_BORROWER, ROLE_INVESTOR).
     * <p>
     * Nếu role không tồn tại trên Keycloak, log cảnh báo và ném exception.
     */
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

    /**
     * Thu hồi refresh token — best-effort, không ném exception nếu thất bại.
     * Gọi token revocation endpoint của Keycloak.
     */
    public void revokeRefreshToken(String refreshToken) {
        String revokeUrl = properties.serverUrl() + "/realms/" + properties.realm()
                + "/protocol/openid-connect/revoke";

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("client_id", properties.clientId());
        params.add("client_secret", properties.clientSecret());
        params.add("token", refreshToken);
        params.add("token_type_hint", "refresh_token");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            restTemplate.exchange(revokeUrl, HttpMethod.POST,
                    new HttpEntity<>(params, headers), Void.class);
            log.debug("Đã thu hồi refresh token thành công");
        } catch (Exception e) {
            // Best-effort — log nhưng không ném exception
            log.warn("Không thể thu hồi refresh token: {}", e.getMessage());
        }
    }

    /**
     * Xóa user trên Keycloak — dùng cho rollback khi tạo DB record thất bại.
     * Best-effort: log lỗi nhưng không ném exception.
     */
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
