package com.finora.user.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Feign client gọi finora-notification để gửi email.
 * <p>
 * Thay thế Kafka producer — giao tiếp đồng bộ qua HTTP.
 * Sau này nếu cần async, swap lại Kafka mà không đổi interface service layer.
 */
@FeignClient(
        name = "notification-service",
        url = "${finora.notification.url:http://localhost:8086}"
)
public interface NotificationClient {

    @PostMapping("/api/internal/notifications/welcome-email")
    void sendWelcomeEmail(@RequestBody WelcomeEmailRequest request);

    @PostMapping("/api/internal/notifications/otp-email")
    void sendOtpEmail(@RequestBody OtpEmailRequest request);

    @PostMapping("/api/internal/notifications/suspicious-activity-alert")
    void sendSuspiciousActivityAlert(@RequestBody SuspiciousActivityAlertRequest request);

    // ── Request DTOs ────────────────────────────────────────────────

    record WelcomeEmailRequest(String email, String fullName) {
    }

    record OtpEmailRequest(String email, String otp) {
    }

    record SuspiciousActivityAlertRequest(String email, String ipAddress, String reason) {
    }
}
