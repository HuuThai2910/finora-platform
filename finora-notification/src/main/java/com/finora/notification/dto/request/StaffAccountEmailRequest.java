package com.finora.notification.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request gửi email thông tin đăng nhập cho tài khoản nhân viên do quản trị viên tạo (finora-user).
 * <p>
 * Mang mật khẩu tạm — không bao giờ ghi log. Mật khẩu chỉ nhận đúng bảng ký tự finora-user sinh ra để email mang
 * danh FINORA không chở được nội dung tùy ý.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StaffAccountEmailRequest {

    @NotBlank @Email @Size(max = 254)
    private String email;

    @Size(max = 255)
    private String fullName;

    /** Tên vai trò hiển thị, ví dụ "Quản trị viên", "Nhân viên (Thu hồi nợ)". */
    @NotBlank @Size(max = 120)
    private String roleName;

    @NotBlank @Pattern(regexp = "[A-Za-z0-9!@#$%*_+=?-]{12,64}")
    private String temporaryPassword;
}
