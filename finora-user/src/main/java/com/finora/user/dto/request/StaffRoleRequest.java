package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Tạo hoặc sửa vai trò nhân viên. Mã vai trò sinh từ {@code name} khi tạo và không đổi khi sửa tên.
 * <p>
 * {@code permissions} là tập con của {@code OpsFunction.allPermissions()}; có quyền Thao tác mà thiếu quyền
 * Xem cùng chức năng thì hệ thống tự thêm quyền Xem.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StaffRoleRequest {

    @NotBlank(message = "Tên vai trò không được để trống")
    @Size(max = 100, message = "Tên vai trò tối đa 100 ký tự")
    private String name;

    @Size(max = 255, message = "Mô tả tối đa 255 ký tự")
    private String description;

    @NotNull(message = "Danh sách quyền không được để trống")
    private List<String> permissions;
}
