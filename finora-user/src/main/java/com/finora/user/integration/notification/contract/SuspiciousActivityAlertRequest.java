package com.finora.user.integration.notification.contract;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request gửi cảnh báo hoạt động bất thường — contract với finora-notification.
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
