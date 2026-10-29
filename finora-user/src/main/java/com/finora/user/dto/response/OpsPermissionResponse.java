package com.finora.user.dto.response;

/**
 * Một chức năng trong danh mục phân quyền nhân viên ({@code OpsFunction}).
 *
 * @param code   mã chức năng, vd. {@code loan-application}
 * @param label  tên chức năng hiển thị
 * @param group  nhóm chức năng trên menu web quản trị
 * @param view   quyền Xem, {@code ops:<code>:view}
 * @param action quyền Thao tác {@code ops:<code>:action}; {@code null} khi chức năng chỉ có quyền Xem
 */
public record OpsPermissionResponse(String code, String label, String group, String view, String action) {
}
