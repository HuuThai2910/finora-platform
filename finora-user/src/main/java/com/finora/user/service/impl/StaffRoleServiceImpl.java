package com.finora.user.service.impl;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.ops.OpsFunction;
import com.finora.user.domain.StaffRoleDefinition;
import com.finora.user.dto.request.StaffRoleRequest;
import com.finora.user.dto.response.OpsPermissionResponse;
import com.finora.user.dto.response.StaffRoleResponse;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.StaffRoleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cấu hình vai trò nhân viên (màn Phân quyền của web quản trị).
 * <p>
 * Vai trò là realm role {@code STAFF_*} trên Keycloak gom các client role {@code finora-ops}; DB chỉ giữ mã vai
 * trò trên hồ sơ để đếm thành viên. Chỉ ADMIN gọi được các thao tác ở đây (kiểm ở controller) — quyền cấu hình
 * vai trò cố ý không nằm trong danh mục {@link OpsFunction} để nhân viên không tự nâng quyền.
 * <p>
 * Đổi quyền của một vai trò thì đăng xuất mọi thành viên: token đang cầm vẫn mang quyền cũ tới khi hết hạn.
 */
@Service
@Slf4j
public class StaffRoleServiceImpl implements StaffRoleService {

    /** Tên vai trò hiển thị ở danh sách người dùng đọc lại từ Keycloak sau mỗi khoảng này. */
    private static final Duration NAME_CACHE_TTL = Duration.ofSeconds(60);

    private final KeycloakAdminService keycloakAdminService;
    private final UserProfileRepository userProfileRepository;
    private final Clock clock;

    private volatile Map<String, String> nameCache = Map.of();
    private volatile Instant nameCacheLoadedAt = Instant.MIN;

    public StaffRoleServiceImpl(KeycloakAdminService keycloakAdminService,
                                UserProfileRepository userProfileRepository,
                                Clock clock) {
        this.keycloakAdminService = keycloakAdminService;
        this.userProfileRepository = userProfileRepository;
        this.clock = clock;
    }

    // ── Danh mục quyền ──────────────────────────────────────────────

    @Override
    public List<OpsPermissionResponse> permissionCatalog() {
        return Arrays.stream(OpsFunction.values())
                .map(function -> new OpsPermissionResponse(function.code(), function.label(), function.group(),
                        function.view(), function.hasAction() ? function.action() : null))
                .toList();
    }

    // ── CRUD vai trò ────────────────────────────────────────────────

    @Override
    public List<StaffRoleResponse> list() {
        Map<String, Long> memberCounts = memberCounts();
        List<StaffRoleDefinition> roles = new ArrayList<>(keycloakAdminService.listStaffRoles());
        roles.sort(Comparator.comparing((StaffRoleDefinition role) -> !StaffRoleService.isBuiltIn(role.code()))
                .thenComparing(StaffRoleDefinition::name, String.CASE_INSENSITIVE_ORDER));
        rememberNames(roles);
        return roles.stream()
                .map(role -> toResponse(role, memberCounts.getOrDefault(role.code(), 0L)))
                .toList();
    }

    @Override
    public StaffRoleResponse create(StaffRoleRequest request) {
        String name = request.getName().trim();
        String code = StaffRoleService.toCode(name);
        List<String> permissions = StaffRoleService.normalizePermissions(request.getPermissions());
        StaffRoleDefinition role = new StaffRoleDefinition(code, name, blankToNull(request.getDescription()),
                permissions);

        keycloakAdminService.createStaffRole(role);
        invalidateNames();
        log.info("Admin đã tạo vai trò nhân viên {} ({} quyền)", code, permissions.size());
        return toResponse(role, 0L);
    }

    @Override
    public StaffRoleResponse update(String code, StaffRoleRequest request) {
        requireStaffRole(code);
        List<String> permissions = StaffRoleService.normalizePermissions(request.getPermissions());
        StaffRoleDefinition role = new StaffRoleDefinition(code, request.getName().trim(),
                blankToNull(request.getDescription()), permissions);

        keycloakAdminService.updateStaffRole(role);
        invalidateNames();

        List<UUID> members = userProfileRepository.findKeycloakUserIdsByStaffRoleCode(code);
        members.forEach(member -> keycloakAdminService.logoutUser(member.toString()));
        log.info("Admin đã cập nhật vai trò nhân viên {} ({} quyền), đăng xuất {} thành viên",
                code, permissions.size(), members.size());
        return toResponse(role, members.size());
    }

    @Override
    public void delete(String code) {
        if (StaffRoleService.isBuiltIn(code)) {
            throw new BusinessException(HttpStatus.CONFLICT, "STAFF_ROLE_BUILT_IN",
                    "Không xoá được vai trò có sẵn của hệ thống");
        }
        requireStaffRole(code);
        long members = userProfileRepository.countByStaffRoleCode(code);
        if (members > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "STAFF_ROLE_IN_USE",
                    "Vai trò đang được gán cho " + members + " tài khoản, hãy đổi vai trò của họ trước khi xoá");
        }
        keycloakAdminService.deleteStaffRole(code);
        invalidateNames();
        log.info("Admin đã xoá vai trò nhân viên {}", code);
    }

    @Override
    public StaffRoleDefinition requireStaffRole(String code) {
        if (code == null || !code.startsWith(CODE_PREFIX)) {
            throw notFound(code);
        }
        return keycloakAdminService.findStaffRole(code).orElseThrow(() -> notFound(code));
    }

    // ── Tên hiển thị (danh sách người dùng, /users/me) ─────────────

    @Override
    public String nameOf(String code) {
        if (code == null) {
            return null;
        }
        Instant now = clock.instant();
        if (!now.isBefore(nameCacheLoadedAt.plus(NAME_CACHE_TTL))) {
            try {
                rememberNames(keycloakAdminService.listStaffRoles());
            } catch (RuntimeException e) {
                // Không làm hỏng danh sách người dùng chỉ vì thiếu tên vai trò; thử lại sau một chu kỳ.
                log.warn("Không đọc được tên vai trò nhân viên từ Keycloak: {}", e.getMessage());
                nameCacheLoadedAt = now;
            }
        }
        return nameCache.getOrDefault(code, code);
    }

    // ── Helper ──────────────────────────────────────────────────────

    private Map<String, Long> memberCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : userProfileRepository.countGroupedByStaffRoleCode()) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private StaffRoleResponse toResponse(StaffRoleDefinition role, long memberCount) {
        return new StaffRoleResponse(role.code(), role.name(), role.description(), role.permissions(),
                memberCount, StaffRoleService.isBuiltIn(role.code()));
    }

    private void rememberNames(List<StaffRoleDefinition> roles) {
        Map<String, String> names = new HashMap<>();
        roles.forEach(role -> names.put(role.code(), role.name()));
        nameCache = Map.copyOf(names);
        nameCacheLoadedAt = clock.instant();
    }

    private void invalidateNames() {
        nameCacheLoadedAt = Instant.MIN;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException notFound(String code) {
        return new BusinessException(HttpStatus.NOT_FOUND, "STAFF_ROLE_NOT_FOUND",
                "Không tìm thấy vai trò nhân viên " + code);
    }
}
