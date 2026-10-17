package com.finora.notification.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request gửi cảnh báo hoạt động đăng nhập bất thường.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SuspiciousActivityAlertRequest {

    @NotBlank @Email @Size(max = 254)
    private String email;
    @Size(max = 64)
    private String ipAddress;
    @NotBlank @Size(max = 200)
    private String reason;
}
