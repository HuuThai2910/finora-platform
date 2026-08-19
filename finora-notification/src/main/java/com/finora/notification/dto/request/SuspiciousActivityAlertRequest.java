package com.finora.notification.dto.request;

/**
 * Request gửi cảnh báo hoạt động đăng nhập bất thường.
 */
public record SuspiciousActivityAlertRequest(String email, String ipAddress, String reason) {
}
