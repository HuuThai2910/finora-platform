package com.finora.user.domain.enums;

import com.finora.common.enums.user.UserRole;

import java.util.EnumSet;
import java.util.Set;

/**
 * Nhóm tài khoản ở màn quản trị: tài khoản nội bộ (trang Phân quyền) và tài khoản khách hàng (trang Khách hàng).
 * <p>
 * Hai nhóm tách hẳn nhau: nhân viên chỉ do quản trị viên tạo bằng thông tin riêng, khách hàng chỉ tự đăng ký trên app,
 * và không có đường đổi vai trò từ nhóm này sang nhóm kia.
 */
public enum AccountScope {

    /** Quản trị viên và nhân viên vận hành. */
    STAFF(EnumSet.of(UserRole.ADMIN, UserRole.STAFF)),

    /** Người vay và nhà đầu tư. */
    CUSTOMER(EnumSet.of(UserRole.BORROWER, UserRole.INVESTOR));

    private final Set<UserRole> roles;

    AccountScope(Set<UserRole> roles) {
        this.roles = roles;
    }

    /** Các vai trò thuộc nhóm; bản sao để nơi gọi không sửa được hằng số. */
    public Set<UserRole> roles() {
        return EnumSet.copyOf(roles);
    }

    public boolean contains(UserRole role) {
        return role != null && roles.contains(role);
    }

    /** Nhóm của một vai trò. */
    public static AccountScope of(UserRole role) {
        return STAFF.contains(role) ? STAFF : CUSTOMER;
    }
}
