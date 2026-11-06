package com.finora.user.dto.request;

/**
 * Bộ lọc danh sách người dùng ở màn quản trị. Mọi trường đều tùy chọn; rỗng hoặc {@code ALL} là không lọc.
 *
 * @param role          vai trò tài khoản ({@code BORROWER}, {@code INVESTOR}, {@code STAFF}, {@code ADMIN})
 * @param ekycStatus    trạng thái xác minh danh tính
 * @param staffRoleCode mã vai trò nhân viên ({@code STAFF_*}); lọc theo mã thì ngầm hiểu {@code role=STAFF}
 * @param q             chuỗi tìm: chuỗi con của họ tên hoặc email, hoặc đúng số điện thoại / số CCCD
 */
public record UserSearchCriteria(String role, String ekycStatus, String staffRoleCode, String q) {
}
