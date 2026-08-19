package com.finora.notification.controller;

import com.finora.notification.dto.request.OtpEmailRequest;
import com.finora.notification.dto.request.SuspiciousActivityAlertRequest;
import com.finora.notification.dto.request.WelcomeEmailRequest;
import com.finora.notification.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller nhận yêu cầu gửi email từ finora-user (qua Feign).
 * <p>
 * Endpoint internal — chỉ dành cho service-to-service, KHÔNG qua API gateway.
 */
@RestController
@RequestMapping("/api/internal/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final EmailService emailService;

    @PostMapping("/welcome-email")
    public ResponseEntity<Void> sendWelcomeEmail(@RequestBody WelcomeEmailRequest request) {
        emailService.sendWelcomeEmail(request.getEmail(), request.getFullName());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/otp-email")
    public ResponseEntity<Void> sendOtpEmail(@RequestBody OtpEmailRequest request) {
        emailService.sendPasswordResetOtp(request.getEmail(), request.getOtp());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/suspicious-activity-alert")
    public ResponseEntity<Void> sendSuspiciousActivityAlert(@RequestBody SuspiciousActivityAlertRequest request) {
        emailService.sendSuspiciousActivityAlert(request.getEmail(), request.getIpAddress(), request.getReason());
        return ResponseEntity.ok().build();
    }
}
