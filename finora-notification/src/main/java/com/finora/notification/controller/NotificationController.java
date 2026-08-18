package com.finora.notification.controller;

import com.finora.notification.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RequiredArgsConstructor
public class NotificationController {

    private final EmailService emailService;

    /**
     * Gửi email chào mừng người dùng mới đăng ký.
     */
    @PostMapping("/welcome-email")
    public void sendWelcomeEmail(@RequestBody WelcomeEmailRequest request) {
        log.info("Nhận yêu cầu gửi welcome email: email={}", maskEmail(request.email()));
        emailService.sendWelcomeEmail(request.email(), request.fullName());
    }

    /**
     * Gửi email chứa mã OTP đặt lại mật khẩu.
     */
    @PostMapping("/otp-email")
    public void sendOtpEmail(@RequestBody OtpEmailRequest request) {
        log.info("Nhận yêu cầu gửi OTP email: email={}", maskEmail(request.email()));
        emailService.sendPasswordResetOtp(request.email(), request.otp());
    }

    /**
     * Gửi email cảnh báo hoạt động đăng nhập bất thường.
     */
    @PostMapping("/suspicious-activity-alert")
    public void sendSuspiciousActivityAlert(@RequestBody SuspiciousActivityAlertRequest request) {
        log.info("Nhận yêu cầu gửi cảnh báo: email={}, lý do={}",
                maskEmail(request.email()), request.reason());
        emailService.sendSuspiciousActivityAlert(request.email(), request.ipAddress(), request.reason());
    }

    // ── Request DTOs ────────────────────────────────────────────────

    public record WelcomeEmailRequest(String email, String fullName) {
    }

    public record OtpEmailRequest(String email, String otp) {
    }

    public record SuspiciousActivityAlertRequest(String email, String ipAddress, String reason) {
    }

    // ── Helper ──────────────────────────────────────────────────────

    /**
     * Mask email đơn giản cho log — hiện 2 ký tự đầu + domain.
     */
    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return "***";
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) return "***";
        int visibleChars = Math.min(2, atIndex);
        return email.substring(0, visibleChars) + "***" + email.substring(atIndex);
    }
}
