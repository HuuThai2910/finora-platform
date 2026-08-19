package com.finora.user.integration.notification.client;

import com.finora.user.integration.notification.contract.OtpEmailRequest;
import com.finora.user.integration.notification.contract.SuspiciousActivityAlertRequest;
import com.finora.user.integration.notification.contract.WelcomeEmailRequest;
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
}
