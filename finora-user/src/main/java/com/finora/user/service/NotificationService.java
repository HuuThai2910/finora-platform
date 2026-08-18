package com.finora.user.service;

import com.finora.user.client.NotificationClient;
import com.finora.user.client.NotificationClient.*;
import com.finora.user.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dịch vụ gửi thông báo — gọi finora-notification qua Feign (best-effort).
 * <p>
 * Mọi lỗi gửi được log nhưng KHÔNG ném exception — đảm bảo luồng nghiệp vụ chính
 * (đăng ký, đăng nhập, quên mật khẩu) không bị ảnh hưởng khi notification service lỗi.
 * <p>
 * PII được mask trước khi ghi log.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationClient notificationClient;

    /**
     * Gửi email chào mừng người dùng mới đăng ký.
     */
    public void sendWelcomeEmail(Long userId, String email, String fullName) {
        try {
            notificationClient.sendWelcomeEmail(new WelcomeEmailRequest(email, fullName));
            log.info("Đã gửi welcome email: userId={}, email={}",
                    userId, PiiMasker.maskEmail(email));
        } catch (Exception e) {
            log.error("Lỗi gửi welcome email: userId={}, email={}, lỗi={}",
                    userId, PiiMasker.maskEmail(email), e.getMessage());
        }
    }

    /**
     * Gửi email chứa mã OTP đặt lại mật khẩu.
     * <p>
     * OTP KHÔNG được ghi rõ trong log — chỉ hiện dạng mask (******).
     */
    public void sendPasswordResetOtp(Long userId, String email, String otp) {
        try {
            notificationClient.sendOtpEmail(new OtpEmailRequest(email, otp));
            log.info("Đã gửi OTP email: userId={}, email={}, otp={}",
                    userId, PiiMasker.maskEmail(email), PiiMasker.maskOtp(otp));
        } catch (Exception e) {
            log.error("Lỗi gửi OTP email: userId={}, email={}, lỗi={}",
                    userId, PiiMasker.maskEmail(email), e.getMessage());
        }
    }

    /**
     * Gửi email cảnh báo hoạt động đăng nhập bất thường.
     */
    public void sendSuspiciousActivityAlert(Long userId, String email,
                                             String ipAddress, String userAgent,
                                             String reason) {
        try {
            notificationClient.sendSuspiciousActivityAlert(
                    new SuspiciousActivityAlertRequest(email, ipAddress, reason));
            log.warn("Đã gửi cảnh báo: userId={}, email={}, lý do={}",
                    userId, PiiMasker.maskEmail(email), reason);
        } catch (Exception e) {
            log.error("Lỗi gửi cảnh báo: userId={}, email={}, lỗi={}",
                    userId, PiiMasker.maskEmail(email), e.getMessage());
        }
    }
}
