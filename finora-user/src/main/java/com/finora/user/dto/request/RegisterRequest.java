package com.finora.user.dto.request;

import com.finora.user.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Yêu cầu đăng ký tài khoản mới.
 * <p>
 * Nếu {@code role} là {@code null}, hệ thống mặc định gán {@link UserRole#BORROWER}.
 */
public record RegisterRequest(

        @NotBlank @Email
        String email,

        @NotBlank @Size(min = 8, max = 64)
        String password,

        @NotBlank @Size(max = 255)
        String fullName,

        /** Vai trò mong muốn — mặc định BORROWER nếu null */
        UserRole role
) {
}
