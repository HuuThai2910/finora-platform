package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Yêu cầu tự đổi mật khẩu khi đang đăng nhập (kể cả đổi mật khẩu tạm ở lần đăng nhập đầu của nhân viên).
 * Mật khẩu mới theo cùng quy tắc độ dài với đăng ký và đặt lại mật khẩu.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    /** Mật khẩu đang dùng (hoặc mật khẩu tạm được cấp) — xác minh lại với Keycloak trước khi đổi. */
    @NotBlank(message = "Vui lòng nhập mật khẩu hiện tại")
    @Size(max = 64)
    private String currentPassword;

    @NotBlank(message = "Vui lòng nhập mật khẩu mới")
    @Size(min = 8, max = 64, message = "Mật khẩu mới dài từ 8 đến 64 ký tự")
    private String newPassword;
}
