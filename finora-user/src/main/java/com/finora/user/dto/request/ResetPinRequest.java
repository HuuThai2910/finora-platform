package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Quên mã PIN — xác minh lại bằng mật khẩu tài khoản rồi đặt PIN mới.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ResetPinRequest {

    @NotBlank
    @Size(max = 64)
    private String password;

    @NotBlank
    @Pattern(regexp = "[0-9]{6}", message = "Mã PIN gồm đúng 6 chữ số")
    private String newPin;
}
