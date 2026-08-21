package com.finora.user.dto.request;

import com.finora.user.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Yêu cầu đăng ký tài khoản mới — bước 1, chỉ nhận thông tin và gửi OTP.
 * <p>
 * Tài khoản chưa được tạo ở bước này. Thông tin được giữ tạm cho tới khi
 * người dùng xác thực OTP qua {@link VerifyRegistrationRequest}.
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

    /** Số điện thoại Việt Nam 10 chữ số — tuỳ chọn, dùng cho hồ sơ và eKYC sau này */
    @Pattern(regexp = "^0[0-9]{9}$", message = "Số điện thoại gồm 10 chữ số và bắt đầu bằng 0")
    private String phone;

    /** Vai trò mong muốn — mặc định BORROWER nếu null */
    private UserRole role;
}
