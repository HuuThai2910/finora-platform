package com.finora.user.service;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ops.OpsFunction;
import com.finora.user.domain.StaffRoleDefinition;
import com.finora.user.dto.request.StaffRoleRequest;
import com.finora.user.dto.response.OpsPermissionResponse;
import com.finora.user.dto.response.StaffRoleResponse;
import com.finora.user.repository.UserProfileRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
public class StaffRoleService {

    /** Vai trò có sẵn "Nhân viên vận hành" — tạo bằng realm JSON/script, không xoá được qua API. */
    public static final String BUILT_IN_CODE = "STAFF_VAN_HANH";

    public static final String CODE_PREFIX = "STAFF_";

    /** Độ dài tối đa phần sinh từ tên (không kể tiền tố). */
    private static final int MAX_SLUG_LENGTH = 40;

    /** Tên vai trò hiển thị ở danh sách người dùng đọc lại từ Keycloak sau mỗi khoảng này. */
    private static final Duration NAME_CACHE_TTL = Duration.ofSeconds(60);

    private final KeycloakAdminService keycloakAdminService;
    private final UserProfileRepository userProfileRepository;
    private final Clock clock;

    private volatile Map<String, String> nameCache = Map.of();
    private volatile Instant nameCacheLoadedAt = Instant.MIN;

    public StaffRoleService(KeycloakAdminService keycloakAdminService,
                            UserProfileRepository userProfileRepository,
                            Clock clock) {
        this.keycloakAdminService = keycloakAdminService;
        this.userProfileRepository = userProfileRepository;
        this.clock = clock;
    }

    // ── Danh mục quyền ──────────────────────────────────────────────

    /** Danh mục chức năng theo đúng thứ tự khai báo, kèm quyền Xem/Thao tác. */
    public List<OpsPermissionResponse> permissionCatalog() {
        return Arrays.stream(OpsFunction.values())
                .map(function -> new OpsPermissionResponse(function.code(), function.label(), function.group(),
                        function.view(), function.hasAction() ? function.action() : null))
                .toList();
    }

    // ── CRUD vai trò ────────────────────────────────────────────────

    /** Mọi vai trò nhân viên, vai trò có sẵn đứng đầu, còn lại theo tên. */
    public List<StaffRoleResponse> list() {
        Map<String, Long> memberCounts = memberCounts();
        List<StaffRoleDefinition> roles = new ArrayList<>(keycloakAdminService.listStaffRoles());
        roles.sort(Comparator.comparing((StaffRoleDefinition role) -> !isBuiltIn(role.code()))
                .thenComparing(StaffRoleDefinition::name, String.CASE_INSENSITIVE_ORDER));
        rememberNames(roles);
        return roles.stream()
                .map(role -> toResponse(role, memberCounts.getOrDefault(role.code(), 0L)))
                .toList();
    }

    public StaffRoleResponse create(StaffRoleRequest request) {
        String name = request.getName().trim();
        String code = toCode(name);
        List<String> permissions = normalizePermissions(request.getPermissions());
        StaffRoleDefinition role = new StaffRoleDefinition(code, name, blankToNull(request.getDescription()),
                permissions);

        keycloakAdminService.createStaffRole(role);
        invalidateNames();
        log.info("Admin đã tạo vai trò nhân viên {} ({} quyền)", code, permissions.size());
        return toResponse(role, 0L);
    }

    /**
     * Đổi tên, mô tả và quyền của vai trò; mã vai trò giữ nguyên. Sau đó đăng xuất thành viên để lần đăng nhập
     * kế tiếp nhận đúng quyền mới.
     */
    public StaffRoleResponse update(String code, StaffRoleRequest request) {
        requireStaffRole(code);
        List<String> permissions = normalizePermissions(request.getPermissions());
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

    public void delete(String code) {
        if (isBuiltIn(code)) {
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

    /** Vai trò nhân viên theo mã, không có thì {@code 404 STAFF_ROLE_NOT_FOUND}. */
    public StaffRoleDefinition requireStaffRole(String code) {
        if (code == null || !code.startsWith(CODE_PREFIX)) {
            throw notFound(code);
        }
        return keycloakAdminService.findStaffRole(code).orElseThrow(() -> notFound(code));
    }

    // ── Tên hiển thị (danh sách người dùng, /users/me) ─────────────

    /**
     * Tên hiển thị của vai trò, đọc từ bộ nhớ đệm 60 giây để danh sách người dùng không gọi Keycloak mỗi dòng.
     * Keycloak lỗi hoặc vai trò đã bị xoá thì trả lại chính mã vai trò.
     */
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

    // ── Quyền của phiên hiện tại ────────────────────────────────────

    /**
     * Quyền {@code ops:*} trong tập authority của JWT, theo thứ tự danh mục. ADMIN nhận đủ danh mục vì luôn qua
     * mọi kiểm tra quyền, kể cả khi realm chưa gắn composite cho admin.
     */
    public static List<String> permissionsOf(Collection<String> authorities) {
        if (authorities.contains(SecurityUtils.ROLE_ADMIN)) {
            return OpsFunction.allPermissions();
        }
        return authorities.stream()
                .filter(authority -> authority.startsWith(OpsFunction.PREFIX))
                .distinct()
                .sorted(Comparator.comparingInt(StaffRoleService::catalogIndex)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    // ── Quy tắc mã và quyền ─────────────────────────────────────────

    /**
     * Mã vai trò từ tên: bỏ dấu tiếng Việt, viết hoa, ký tự khác chữ/số thành {@code _}.
     * Vd. "Nhân viên thu hồi nợ" → {@code STAFF_NHAN_VIEN_THU_HOI_NO}.
     */
    public static String toCode(String name) {
        String ascii = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toUpperCase(Locale.ROOT);
        String slug = ascii.replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.startsWith(CODE_PREFIX)) {
            slug = slug.substring(CODE_PREFIX.length());
        }
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH).replaceAll("_+$", "");
        }
        if (slug.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STAFF_ROLE_NAME",
                    "Tên vai trò phải có ít nhất một chữ cái hoặc chữ số");
        }
        return CODE_PREFIX + slug;
    }

    /**
     * Kiểm quyền thuộc danh mục, thêm quyền Xem đi kèm mỗi quyền Thao tác, bỏ trùng và xếp theo danh mục.
     */
    public static List<String> normalizePermissions(Collection<String> requested) {
        Set<String> result = new LinkedHashSet<>();
        for (String permission : requested) {
            if (permission == null || !OpsFunction.isKnownPermission(permission)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "UNKNOWN_PERMISSION",
                        "Quyền không có trong danh mục: " + permission);
            }
            result.add(permission);
            if (permission.endsWith(":action")) {
                result.add(permission.substring(0, permission.length() - ":action".length()) + ":view");
            }
        }
        return result.stream().sorted(Comparator.comparingInt(StaffRoleService::catalogIndex)).toList();
    }

    public static boolean isBuiltIn(String code) {
        return BUILT_IN_CODE.equals(code);
    }

    // ── Helper ──────────────────────────────────────────────────────

    private static int catalogIndex(String permission) {
        int index = OpsFunction.allPermissions().indexOf(permission);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private Map<String, Long> memberCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : userProfileRepository.countGroupedByStaffRoleCode()) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private StaffRoleResponse toResponse(StaffRoleDefinition role, long memberCount) {
        return new StaffRoleResponse(role.code(), role.name(), role.description(), role.permissions(),
                memberCount, isBuiltIn(role.code()));
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
