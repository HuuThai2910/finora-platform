package com.finora.user.dto.request;

import com.finora.user.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Yêu cầu đăng ký tài khoản mới.
 * <p>
 * Nếu {@code role} là {@code null}, hệ thống mặc định gán {@link UserRole#BORROWER}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8, max = 64)
    private String password;

    @NotBlank
    @Size(max = 255)
    private String fullName;

    /** Vai trò mong muốn — mặc định BORROWER nếu null */
    private UserRole role;
}
