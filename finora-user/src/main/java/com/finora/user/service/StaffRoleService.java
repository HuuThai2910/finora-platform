package com.finora.user.service;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ops.OpsFunction;
import com.finora.user.domain.StaffRoleDefinition;
import com.finora.user.dto.request.StaffRoleRequest;
import com.finora.user.dto.response.OpsPermissionResponse;
import com.finora.user.dto.response.StaffRoleResponse;
import org.springframework.http.HttpStatus;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cấu hình vai trò nhân viên (màn Phân quyền của web quản trị).
 */
public interface StaffRoleService {

    /** Vai trò có sẵn "Nhân viên vận hành" — tạo bằng realm JSON/script, không xoá được qua API. */
    String BUILT_IN_CODE = "STAFF_VAN_HANH";

    String CODE_PREFIX = "STAFF_";

    /** Độ dài tối đa phần sinh từ tên (không kể tiền tố). */
    int MAX_SLUG_LENGTH = 40;

    /** Danh mục chức năng theo đúng thứ tự khai báo, kèm quyền Xem/Thao tác. */
    List<OpsPermissionResponse> permissionCatalog();

    /** Mọi vai trò nhân viên, vai trò có sẵn đứng đầu, còn lại theo tên. */
    List<StaffRoleResponse> list();

    StaffRoleResponse create(StaffRoleRequest request);

    /**
     * Đổi tên, mô tả và quyền của vai trò; mã vai trò giữ nguyên. Sau đó đăng xuất thành viên để lần đăng nhập
     * kế tiếp nhận đúng quyền mới.
     */
    StaffRoleResponse update(String code, StaffRoleRequest request);

    void delete(String code);

    /** Vai trò nhân viên theo mã, không có thì {@code 404 STAFF_ROLE_NOT_FOUND}. */
    StaffRoleDefinition requireStaffRole(String code);

    /**
     * Tên hiển thị của vai trò, đọc từ bộ nhớ đệm 60 giây để danh sách người dùng không gọi Keycloak mỗi dòng.
     * Keycloak lỗi hoặc vai trò đã bị xoá thì trả lại chính mã vai trò.
     */
    String nameOf(String code);

    /**
     * Quyền {@code ops:*} trong tập authority của JWT, theo thứ tự danh mục. ADMIN nhận đủ danh mục vì luôn qua
     * mọi kiểm tra quyền, kể cả khi realm chưa gắn composite cho admin.
     */
    static List<String> permissionsOf(Collection<String> authorities) {
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

    /**
     * Mã vai trò từ tên: bỏ dấu tiếng Việt, viết hoa, ký tự khác chữ/số thành {@code _}.
     * Vd. "Nhân viên thu hồi nợ" → {@code STAFF_NHAN_VIEN_THU_HOI_NO}.
     */
    static String toCode(String name) {
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
    static List<String> normalizePermissions(Collection<String> requested) {
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

    static boolean isBuiltIn(String code) {
        return BUILT_IN_CODE.equals(code);
    }

    private static int catalogIndex(String permission) {
        int index = OpsFunction.allPermissions().indexOf(permission);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
