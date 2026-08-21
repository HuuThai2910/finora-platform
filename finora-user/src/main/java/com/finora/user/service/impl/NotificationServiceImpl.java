package com.finora.user.service.impl;

import com.finora.user.integration.notification.client.NotificationClient;
import com.finora.user.integration.notification.contract.OtpEmailRequest;
import com.finora.user.integration.notification.contract.SuspiciousActivityAlertRequest;
import com.finora.user.integration.notification.contract.WelcomeEmailRequest;
import com.finora.user.service.NotificationService;
import com.finora.user.support.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Triển khai dịch vụ gửi thông báo — gọi finora-notification qua Feign (best-effort).
 * <p>
 * Mọi lỗi gửi được log nhưng KHÔNG ném exception — đảm bảo luồng nghiệp vụ chính
 * (đăng ký, đăng nhập, quên mật khẩu) không bị ảnh hưởng khi notification service lỗi.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationClient notificationClient;

    @Override
    public void sendWelcomeEmail(Long userId, String email, String fullName) {
        try {
            // Chào bằng tên gọi (chữ cuối của họ tên). Đăng ký không thu họ tên
            // nên có thể chưa có — khi đó gửi rỗng để template chào không tên,
            // tuyệt đối không thay bằng địa chỉ email.
            notificationClient.sendWelcomeEmail(new WelcomeEmailRequest(email, givenName(fullName)));
            log.info("Đã gửi welcome email: userId={}, email={}",
                    userId, PiiMasker.maskEmail(email));
        } catch (Exception e) {
            log.error("Lỗi gửi welcome email: userId={}, email={}, lỗi={}",
                    userId, PiiMasker.maskEmail(email), e.getMessage());
        }
    }

    /** Tên gọi = chữ cuối của họ tên Việt Nam; chưa có tên thì trả chuỗi rỗng. */
    private static String givenName(String fullName) {
        if (fullName == null || fullName.isBlank()) return "";
        String[] words = fullName.trim().split("\\s+");
        return words[words.length - 1];
    }

    @Override
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

    @Override
    public void sendRegistrationOtp(String email, String otp) {
        try {
            notificationClient.sendOtpEmail(new OtpEmailRequest(email, otp));
            log.info("Đã gửi OTP đăng ký: email={}, otp={}",
                    PiiMasker.maskEmail(email), PiiMasker.maskOtp(otp));
        } catch (Exception e) {
            log.error("Lỗi gửi OTP đăng ký: email={}, lỗi={}",
                    PiiMasker.maskEmail(email), e.getMessage());
        }
    }

    @Override
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
