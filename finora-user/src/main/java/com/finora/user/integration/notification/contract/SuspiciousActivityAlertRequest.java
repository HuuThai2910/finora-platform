package com.finora.user.integration.notification.contract;

/**
 * Request gửi cảnh báo hoạt động bất thường — contract với finora-notification.
 */
public record SuspiciousActivityAlertRequest(String email, String ipAddress, String reason) {
}
