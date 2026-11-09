package com.finora.user.dto.request;

/**
 * Bộ lọc danh sách người dùng ở màn quản trị. Mọi trường đều tùy chọn; rỗng hoặc {@code ALL} là không lọc.
 *
 * @param role          vai trò tài khoản ({@code BORROWER}, {@code INVESTOR}, {@code STAFF}, {@code ADMIN})
 * @param ekycStatus    trạng thái xác minh danh tính
 * @param staffRoleCode mã vai trò nhân viên ({@code STAFF_*}); lọc theo mã thì ngầm hiểu {@code role=STAFF}
 * @param q             chuỗi tìm: chuỗi con của họ tên hoặc email, hoặc đúng số điện thoại / số CCCD
 * @param scope         nhóm tài khoản: {@code STAFF} (quản trị viên + nhân viên) hoặc {@code CUSTOMER} (người vay +
 *                      nhà đầu tư — trang Khách hàng); lọc ở DB nên phân trang và tổng số đúng theo nhóm
 * @param filter        bộ lọc nâng cao (ngày tạo, khóa, tuổi, giới tính, lần đăng nhập cuối); {@code null} là không lọc
 */
public record UserSearchCriteria(String role, String ekycStatus, String staffRoleCode, String q, String scope,
                                 UserListFilter filter) {

    public UserSearchCriteria {
        filter = filter == null ? UserListFilter.NONE : filter;
    }

    /** Không có bộ lọc nâng cao. */
    public UserSearchCriteria(String role, String ekycStatus, String staffRoleCode, String q, String scope) {
        this(role, ekycStatus, staffRoleCode, q, scope, UserListFilter.NONE);
    }

    /** Bộ lọc không giới hạn nhóm tài khoản (giữ cho nơi gọi cũ). */
    public UserSearchCriteria(String role, String ekycStatus, String staffRoleCode, String q) {
        this(role, ekycStatus, staffRoleCode, q, null, UserListFilter.NONE);
    }
}
