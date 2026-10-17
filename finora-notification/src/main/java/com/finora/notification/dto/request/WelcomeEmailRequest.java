package com.finora.notification.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request gửi email chào mừng người dùng mới.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WelcomeEmailRequest {

    @NotBlank @Email @Size(max = 254)
    private String email;
    /** Tên gọi, có thể rỗng khi đăng ký chưa thu họ tên. */
    @Size(max = 100)
    private String fullName;
}
