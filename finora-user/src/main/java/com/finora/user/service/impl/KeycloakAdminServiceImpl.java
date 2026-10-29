package com.finora.user.service.impl;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.ops.OpsFunction;
import com.finora.user.domain.StaffRoleDefinition;
import com.finora.user.config.KeycloakAdminProperties;
import com.finora.common.enums.user.UserRole;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.support.PiiMasker;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** Tên attribute trên Keycloak, phải khớp {@code user.attribute} của protocol mapper "user-id". */
    private static final String USER_ID_ATTRIBUTE = "user_id";

    /** Phải khớp {@code user.attribute} của protocol mapper "ekyc-status". */
    private static final String EKYC_STATUS_ATTRIBUTE = "ekyc_status";

    /** Tiền tố realm role của vai trò nhân viên. */
    private static final String STAFF_ROLE_PREFIX = "STAFF_";

    /** Attribute đánh dấu realm role là vai trò nhân viên (phân biệt với role kỹ thuật trùng tiền tố). */
    private static final String STAFF_ROLE_ATTRIBUTE = "finoraStaffRole";

    /** Attribute giữ tên hiển thị có dấu của vai trò nhân viên. */
    private static final String DISPLAY_NAME_ATTRIBUTE = "displayName";

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

    /** Package-private để test thay bằng mock, không phải dựng Keycloak thật. */
    RealmResource getRealmResource() {
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
    public void setUserIdAttribute(String keycloakUserId, Long userId) {
        try {
            var userResource = getRealmResource().users().get(keycloakUserId);
            UserRepresentation user = userResource.toRepresentation();

            // Keycloak lưu attribute dạng danh sách chuỗi; giữ nguyên các attribute
            // khác để không xoá dữ liệu do luồng nghiệp vụ khác ghi.
            Map<String, List<String>> attributes = user.getAttributes() != null
                    ? new HashMap<>(user.getAttributes())
                    : new HashMap<>();
            attributes.put(USER_ID_ATTRIBUTE, List.of(String.valueOf(userId)));
            user.setAttributes(attributes);

            userResource.update(user);

            log.info("Đã gắn user_id vào Keycloak: keycloakUserId={}, userId={}",
                    keycloakUserId, userId);

        } catch (Exception e) {
            log.error("Lỗi gắn user_id vào Keycloak: keycloakUserId={}, userId={}",
                    keycloakUserId, userId, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể hoàn tất khởi tạo tài khoản, vui lòng thử lại sau");
        }
    }

    @Override
    public void setEkycStatusAttribute(String keycloakUserId, String ekycStatus) {
        try {
            var userResource = getRealmResource().users().get(keycloakUserId);
            UserRepresentation user = userResource.toRepresentation();
            Map<String, List<String>> attributes = user.getAttributes() != null
                    ? new HashMap<>(user.getAttributes())
                    : new HashMap<>();
            if (List.of(ekycStatus).equals(attributes.get(EKYC_STATUS_ATTRIBUTE))) {
                return;
            }
            attributes.put(EKYC_STATUS_ATTRIBUTE, List.of(ekycStatus));
            user.setAttributes(attributes);
            userResource.update(user);
            log.info("Đã ghi ekyc_status vào Keycloak: keycloakUserId={}, ekycStatus={}",
                    keycloakUserId, ekycStatus);
        } catch (Exception e) {
            log.error("Lỗi ghi ekyc_status vào Keycloak: keycloakUserId={}", keycloakUserId, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể cập nhật trạng thái xác minh, vui lòng thử lại sau");
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
    public Boolean isUserEnabled(String keycloakUserId) {
        try {
            UserRepresentation user = getRealmResource().users().get(keycloakUserId).toRepresentation();
            return user.isEnabled();

        } catch (Exception e) {
            // Không ném lỗi: danh sách người dùng vẫn phải hiển thị được khi Keycloak
            // tạm thời không tra cứu được trạng thái khóa.
            log.warn("Không đọc được trạng thái tài khoản Keycloak: keycloakUserId={}", keycloakUserId);
            return null;
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
    public void replaceRealmRoles(String keycloakUserId, List<String> roleNames) {
        try {
            RealmResource realmResource = getRealmResource();
            RoleScopeResource realmRoles = realmResource.users().get(keycloakUserId).roles().realmLevel();

            List<RoleRepresentation> current = realmRoles.listAll();
            Set<String> currentNames = current.stream().map(RoleRepresentation::getName).collect(Collectors.toSet());

            List<RoleRepresentation> toAdd = new ArrayList<>();
            for (String roleName : roleNames) {
                if (!currentNames.contains(roleName)) {
                    toAdd.add(realmResource.roles().get(roleName).toRepresentation());
                }
            }
            List<RoleRepresentation> toRemove = current.stream()
                    .filter(role -> isManagedAccountRole(role.getName()) && !roleNames.contains(role.getName()))
                    .toList();

            if (!toAdd.isEmpty()) {
                realmRoles.add(toAdd);
            }
            if (!toRemove.isEmpty()) {
                realmRoles.remove(toRemove);
            }

            log.info("Đã thay realm role cho keycloakUserId={}: thêm={}, gỡ={}", keycloakUserId,
                    toAdd.stream().map(RoleRepresentation::getName).toList(),
                    toRemove.stream().map(RoleRepresentation::getName).toList());

        } catch (jakarta.ws.rs.NotFoundException e) {
            log.error("Không tìm thấy user hoặc role trên Keycloak khi gán {} cho keycloakUserId={}",
                    roleNames, keycloakUserId);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Vai trò không tồn tại trên hệ thống xác thực: " + String.join(", ", roleNames));
        } catch (Exception e) {
            log.error("Lỗi gán role {} cho keycloakUserId={}", roleNames, keycloakUserId, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Không thể gán vai trò, vui lòng thử lại sau");
        }
    }

    /** Realm role do FINORA quản lý trên tài khoản: role theo {@link UserRole} và vai trò nhân viên. */
    private static boolean isManagedAccountRole(String roleName) {
        if (roleName == null) {
            return false;
        }
        if (roleName.startsWith(STAFF_ROLE_PREFIX)) {
            return true;
        }
        for (UserRole role : UserRole.values()) {
            if (roleName.equals("ROLE_" + role.name())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void logoutUser(String keycloakUserId) {
        try {
            getRealmResource().users().get(keycloakUserId).logout();
            log.info("Đã đăng xuất mọi phiên của keycloakUserId={}", keycloakUserId);
        } catch (Exception e) {
            // Không chặn thao tác chính: token cũ vẫn hết hạn sau accessTokenLifespan, và refresh token
            // luôn nhận quyền mới vì Keycloak tính lại role mỗi lần cấp.
            log.warn("Không đăng xuất được keycloakUserId={}: {}", keycloakUserId, e.getMessage());
        }
    }

    // ── Vai trò nhân viên ───────────────────────────────────────────

    @Override
    public List<StaffRoleDefinition> listStaffRoles() {
        try {
            RealmResource realmResource = getRealmResource();
            String opsClientUuid = opsClientUuid(realmResource);
            List<StaffRoleDefinition> result = new ArrayList<>();
            for (RoleRepresentation role : realmResource.roles().list(false)) {
                if (isStaffRole(role)) {
                    result.add(toStaffRole(role, realmResource, opsClientUuid));
                }
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi đọc danh sách vai trò nhân viên từ Keycloak", e);
            throw staffRoleUnavailable();
        }
    }

    @Override
    public Optional<StaffRoleDefinition> findStaffRole(String code) {
        try {
            RealmResource realmResource = getRealmResource();
            RoleRepresentation role;
            try {
                role = realmResource.roles().get(code).toRepresentation();
            } catch (jakarta.ws.rs.NotFoundException e) {
                return Optional.empty();
            }
            if (!isStaffRole(role)) {
                return Optional.empty();
            }
            return Optional.of(toStaffRole(role, realmResource, opsClientUuid(realmResource)));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi đọc vai trò nhân viên {} từ Keycloak", code, e);
            throw staffRoleUnavailable();
        }
    }

    @Override
    public void createStaffRole(StaffRoleDefinition staffRole) {
        try {
            RealmResource realmResource = getRealmResource();
            String opsClientUuid = opsClientUuid(realmResource);
            List<RoleRepresentation> composites = opsRoles(realmResource, opsClientUuid, staffRole.permissions());

            RoleRepresentation role = new RoleRepresentation();
            role.setName(staffRole.code());
            role.setDescription(staffRole.description());
            role.setAttributes(staffRoleAttributes(new HashMap<>(), staffRole.name()));
            try {
                realmResource.roles().create(role);
            } catch (jakarta.ws.rs.ClientErrorException e) {
                if (e.getResponse() != null && e.getResponse().getStatus() == 409) {
                    throw new BusinessException(HttpStatus.CONFLICT, "STAFF_ROLE_EXISTS",
                            "Đã có vai trò mã " + staffRole.code() + ", hãy đặt tên khác");
                }
                throw e;
            }
            if (!composites.isEmpty()) {
                realmResource.roles().get(staffRole.code()).addComposites(composites);
            }
            log.info("Đã tạo vai trò nhân viên {} với {} quyền", staffRole.code(), composites.size());

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi tạo vai trò nhân viên {} trên Keycloak", staffRole.code(), e);
            throw staffRoleUnavailable();
        }
    }

    @Override
    public void updateStaffRole(StaffRoleDefinition staffRole) {
        try {
            RealmResource realmResource = getRealmResource();
            String opsClientUuid = opsClientUuid(realmResource);
            RoleResource roleResource = realmResource.roles().get(staffRole.code());

            RoleRepresentation role = roleResource.toRepresentation();
            role.setDescription(staffRole.description());
            Map<String, List<String>> attributes = role.getAttributes() != null
                    ? new HashMap<>(role.getAttributes())
                    : new HashMap<>();
            role.setAttributes(staffRoleAttributes(attributes, staffRole.name()));
            roleResource.update(role);

            Set<String> wanted = new HashSet<>(staffRole.permissions());
            Set<RoleRepresentation> current = roleResource.getClientRoleComposites(opsClientUuid);
            Set<String> currentNames = current.stream().map(RoleRepresentation::getName).collect(Collectors.toSet());

            List<String> missing = staffRole.permissions().stream().filter(p -> !currentNames.contains(p)).toList();
            List<RoleRepresentation> extra = current.stream().filter(r -> !wanted.contains(r.getName())).toList();
            if (!missing.isEmpty()) {
                roleResource.addComposites(opsRoles(realmResource, opsClientUuid, missing));
            }
            if (!extra.isEmpty()) {
                roleResource.deleteComposites(extra);
            }
            log.info("Đã cập nhật vai trò nhân viên {}: thêm={}, gỡ={}", staffRole.code(), missing,
                    extra.stream().map(RoleRepresentation::getName).toList());

        } catch (BusinessException e) {
            throw e;
        } catch (jakarta.ws.rs.NotFoundException e) {
            throw staffRoleNotFound(staffRole.code());
        } catch (Exception e) {
            log.error("Lỗi cập nhật vai trò nhân viên {} trên Keycloak", staffRole.code(), e);
            throw staffRoleUnavailable();
        }
    }

    @Override
    public void deleteStaffRole(String code) {
        try {
            getRealmResource().roles().deleteRole(code);
            log.info("Đã xoá vai trò nhân viên {}", code);
        } catch (jakarta.ws.rs.NotFoundException e) {
            throw staffRoleNotFound(code);
        } catch (Exception e) {
            log.error("Lỗi xoá vai trò nhân viên {} trên Keycloak", code, e);
            throw staffRoleUnavailable();
        }
    }

    private static boolean isStaffRole(RoleRepresentation role) {
        if (role == null || role.getName() == null || !role.getName().startsWith(STAFF_ROLE_PREFIX)) {
            return false;
        }
        Map<String, List<String>> attributes = role.getAttributes();
        return attributes != null && attributes.getOrDefault(STAFF_ROLE_ATTRIBUTE, List.of()).contains("true");
    }

    private StaffRoleDefinition toStaffRole(RoleRepresentation role, RealmResource realmResource,
                                            String opsClientUuid) {
        List<String> displayName = role.getAttributes().get(DISPLAY_NAME_ATTRIBUTE);
        String name = displayName != null && !displayName.isEmpty() && !displayName.get(0).isBlank()
                ? displayName.get(0)
                : role.getName();
        List<String> permissions = realmResource.roles().get(role.getName())
                .getClientRoleComposites(opsClientUuid).stream()
                .map(RoleRepresentation::getName)
                .sorted(Comparator.comparingInt(KeycloakAdminServiceImpl::catalogIndex)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        return new StaffRoleDefinition(role.getName(), name, role.getDescription(), permissions);
    }

    /** Vị trí trong danh mục để trả quyền đúng thứ tự hiển thị; quyền lạ (thêm tay trên Keycloak) xếp cuối. */
    private static int catalogIndex(String permission) {
        int index = OpsFunction.allPermissions().indexOf(permission);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static Map<String, List<String>> staffRoleAttributes(Map<String, List<String>> attributes, String name) {
        attributes.put(STAFF_ROLE_ATTRIBUTE, List.of("true"));
        attributes.put(DISPLAY_NAME_ATTRIBUTE, List.of(name));
        return attributes;
    }

    /** UUID nội bộ của client {@code finora-ops}; realm chưa có client này nghĩa là chưa chạy add-staff-rbac.sh. */
    private String opsClientUuid(RealmResource realmResource) {
        List<ClientRepresentation> clients = realmResource.clients().findByClientId(OpsFunction.CLIENT_ID);
        if (clients == null || clients.isEmpty()) {
            log.error("Realm {} chưa có client {} — chạy docker/keycloak/add-staff-rbac.sh",
                    properties.getRealm(), OpsFunction.CLIENT_ID);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "STAFF_RBAC_NOT_CONFIGURED",
                    "Hệ thống xác thực chưa được cấu hình phân quyền nhân viên");
        }
        return clients.get(0).getId();
    }

    /** Client role {@code finora-ops} ứng với danh sách quyền; thiếu role nào là realm chưa đồng bộ danh mục. */
    private List<RoleRepresentation> opsRoles(RealmResource realmResource, String opsClientUuid,
                                              List<String> permissions) {
        if (permissions.isEmpty()) {
            return List.of();
        }
        Map<String, RoleRepresentation> byName = realmResource.clients().get(opsClientUuid).roles().list().stream()
                .collect(Collectors.toMap(RoleRepresentation::getName, role -> role, (a, b) -> a));
        List<RoleRepresentation> result = new ArrayList<>();
        for (String permission : permissions) {
            RoleRepresentation role = byName.get(permission);
            if (role == null) {
                log.error("Client {} thiếu role {} — chạy lại docker/keycloak/add-staff-rbac.sh",
                        OpsFunction.CLIENT_ID, permission);
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "STAFF_RBAC_NOT_CONFIGURED",
                        "Hệ thống xác thực chưa có quyền " + permission);
            }
            result.add(role);
        }
        return result;
    }

    private static BusinessException staffRoleNotFound(String code) {
        return new BusinessException(HttpStatus.NOT_FOUND, "STAFF_ROLE_NOT_FOUND",
                "Không tìm thấy vai trò nhân viên " + code);
    }

    private static BusinessException staffRoleUnavailable() {
        return new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Hệ thống xác thực đang lỗi, chưa xử lý được phân quyền nhân viên, vui lòng thử lại sau");
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
