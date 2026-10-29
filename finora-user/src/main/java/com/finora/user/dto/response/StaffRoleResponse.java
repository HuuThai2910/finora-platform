package com.finora.user.dto.response;

import java.util.List;

/**
 * Một vai trò nhân viên ở màn Phân quyền.
 *
 * @param code        mã vai trò ({@code STAFF_*}), dùng khi gán cho tài khoản
 * @param name        tên hiển thị
 * @param description mô tả, có thể {@code null}
 * @param permissions quyền {@code ops:*} theo thứ tự danh mục
 * @param memberCount số tài khoản đang giữ vai trò (đếm trong DB)
 * @param builtIn     vai trò có sẵn của hệ thống — sửa quyền được nhưng không xoá được
 */
public record StaffRoleResponse(String code, String name, String description, List<String> permissions,
                                long memberCount, boolean builtIn) {
}
