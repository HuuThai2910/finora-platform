package com.finora.notification.service.impl;

import com.finora.notification.service.EmailService;
import com.finora.notification.service.EmailTemplateService;
import com.finora.notification.support.EmailMasker;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Triển khai dịch vụ gửi email — sử dụng Spring Mail.
 * <p>
 * Email là best-effort: lỗi gửi được log nhưng không ném exception
 * để không ảnh hưởng luồng nghiệp vụ chính.
 * <p>
 * HTML template được render bởi {@link EmailTemplateService}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final EmailTemplateService templateService;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Override
    public void sendWelcomeEmail(String toEmail, String fullName) {
        sendHtmlEmail(toEmail,
                "Chào mừng bạn đến với FINORA!",
                templateService.renderWelcomeEmail(fullName));
    }

    @Override
    public void sendPasswordResetOtp(String toEmail, String otp) {
        sendHtmlEmail(toEmail,
                "Mã xác nhận đổi mật khẩu FINORA",
                templateService.renderPasswordResetOtp(otp));
    }

    @Override
    public void sendSuspiciousActivityAlert(String toEmail, String ipAddress, String reason) {
        sendHtmlEmail(toEmail,
                "Cảnh báo hoạt động đăng nhập bất thường — FINORA",
                templateService.renderSuspiciousActivityAlert(ipAddress, reason));
    }

    // ── Helper ──────────────────────────────────────────────────────

    /**
     * Gửi email HTML — best-effort, log lỗi nhưng không ném exception.
     */
    private void sendHtmlEmail(String toEmail, String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Đã gửi email thành công: subject='{}', to='{}'", subject, EmailMasker.mask(toEmail));

        } catch (MessagingException e) {
            log.error("Lỗi gửi email: subject='{}', to='{}', lỗi={}",
                    subject, EmailMasker.mask(toEmail), e.getMessage());
        } catch (Exception e) {
            log.error("Lỗi không mong đợi khi gửi email: subject='{}', to='{}'",
                    subject, EmailMasker.mask(toEmail), e);
        }
    }
}
