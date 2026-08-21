package com.finora.notification.dto.request;

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

    private String email;
    private String ipAddress;
    private String reason;
}
