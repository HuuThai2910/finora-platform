package com.finora.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Yêu cầu đăng nhập bằng email và mật khẩu.
 */
public record LoginRequest(

        @NotBlank @Email
        String email,

        @NotBlank @Size(min = 8)
        String password
) {
}
