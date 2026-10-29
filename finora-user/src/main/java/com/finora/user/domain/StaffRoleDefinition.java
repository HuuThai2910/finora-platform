package com.finora.user.domain;

import java.util.List;

/**
 * Vai trò nhân viên như đang lưu trên Keycloak: realm role {@code STAFF_*} có attribute
 * {@code finoraStaffRole=true}, tên hiển thị ở attribute {@code displayName}, quyền là các client role
 * {@code finora-ops} gom vào composite. Không có bảng riêng trong DB — Keycloak là nguồn sự thật của quyền.
 *
 * @param code        tên realm role, dạng {@code STAFF_<MÃ>}
 * @param name        tên hiển thị (attribute {@code displayName}); thiếu thì dùng {@code code}
 * @param description mô tả của realm role, có thể {@code null}
 * @param permissions quyền {@code ops:*} trong composite, theo thứ tự danh mục
 */
public record StaffRoleDefinition(String code, String name, String description, List<String> permissions) {

    public StaffRoleDefinition {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
