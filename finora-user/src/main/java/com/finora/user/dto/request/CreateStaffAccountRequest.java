package com.finora.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Quản trị viên tạo tài khoản nội bộ (nhân viên hoặc quản trị viên) bằng thông tin riêng của người đó — không lấy tài
 * khoản khách hàng gắn thêm vai trò. Mật khẩu không nhận từ request: hệ thống sinh mật khẩu tạm.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateStaffAccountRequest {

    @NotBlank(message = "Vui lòng nhập họ tên")
    @Size(max = 255, message = "Họ tên tối đa 255 ký tự")
    private String fullName;

    /** Email đăng nhập — duy nhất toàn hệ thống (kể cả tài khoản khách hàng). */
    @NotBlank(message = "Vui lòng nhập email đăng nhập")
    @Email(message = "Email không hợp lệ")
    @Size(max = 254, message = "Email tối đa 254 ký tự")
    private String email;

    /** Số điện thoại Việt Nam 10 chữ số — tuỳ chọn, cùng quy tắc với đăng ký. */
    @Pattern(regexp = "^(0[0-9]{9})?$", message = "Số điện thoại gồm 10 chữ số và bắt đầu bằng 0")
    private String phone;

    /** {@code STAFF} hoặc {@code ADMIN}; vai trò khách hàng bị từ chối (400 ROLE_NOT_ASSIGNABLE). */
    @NotBlank(message = "Vui lòng chọn vai trò")
    private String role;

    /** Mã vai trò nhân viên ({@code STAFF_*}) — bắt buộc khi {@code role=STAFF}, bỏ trống khi {@code role=ADMIN}. */
    private String staffRoleCode;
}
