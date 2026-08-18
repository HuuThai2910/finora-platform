package com.finora.notification.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Dịch vụ gửi email — sử dụng Spring Mail với HTML template inline.
 * <p>
 * Email là best-effort: lỗi gửi được log nhưng không ném exception
 * để không ảnh hưởng luồng nghiệp vụ chính.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    /**
     * Gửi email chào mừng người dùng mới đăng ký.
     */
    public void sendWelcomeEmail(String toEmail, String fullName) {
        String subject = "Chào mừng bạn đến với FINORA!";
        String htmlContent = """
                <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
                    <div style="background: linear-gradient(135deg, #1a73e8, #0d47a1); padding: 30px; border-radius: 12px 12px 0 0; text-align: center;">
                        <h1 style="color: #ffffff; margin: 0; font-size: 28px;">FINORA</h1>
                        <p style="color: #bbdefb; margin-top: 8px; font-size: 14px;">Nền tảng cho vay ngang hàng</p>
                    </div>
                    <div style="background: #ffffff; padding: 30px; border: 1px solid #e0e0e0; border-top: none; border-radius: 0 0 12px 12px;">
                        <h2 style="color: #1a73e8; margin-top: 0;">Xin chào, %s!</h2>
                        <p style="color: #333; line-height: 1.6;">
                            Chào mừng bạn đến với <strong>FINORA</strong> — nền tảng cho vay ngang hàng an toàn và minh bạch.
                        </p>
                        <p style="color: #333; line-height: 1.6;">
                            Tài khoản của bạn đã được tạo thành công. Để bắt đầu sử dụng dịch vụ, vui lòng hoàn tất xác minh danh tính (eKYC) trong phần <strong>Hồ sơ cá nhân</strong>.
                        </p>
                        <div style="text-align: center; margin: 30px 0;">
                            <a href="#" style="background: #1a73e8; color: #ffffff; padding: 12px 32px; border-radius: 6px; text-decoration: none; font-weight: bold;">
                                Hoàn tất hồ sơ
                            </a>
                        </div>
                        <p style="color: #757575; font-size: 13px; margin-top: 30px; border-top: 1px solid #e0e0e0; padding-top: 15px;">
                            Nếu bạn không tạo tài khoản này, vui lòng bỏ qua email này.
                        </p>
                    </div>
                </div>
                """.formatted(escapeHtml(fullName));

        sendHtmlEmail(toEmail, subject, htmlContent);
    }

    /**
     * Gửi email chứa mã OTP đặt lại mật khẩu.
     */
    public void sendPasswordResetOtp(String toEmail, String otp) {
        String subject = "Mã xác nhận đổi mật khẩu FINORA";
        String htmlContent = """
                <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
                    <div style="background: linear-gradient(135deg, #1a73e8, #0d47a1); padding: 30px; border-radius: 12px 12px 0 0; text-align: center;">
                        <h1 style="color: #ffffff; margin: 0; font-size: 28px;">FINORA</h1>
                        <p style="color: #bbdefb; margin-top: 8px; font-size: 14px;">Đặt lại mật khẩu</p>
                    </div>
                    <div style="background: #ffffff; padding: 30px; border: 1px solid #e0e0e0; border-top: none; border-radius: 0 0 12px 12px;">
                        <h2 style="color: #1a73e8; margin-top: 0;">Mã xác nhận của bạn</h2>
                        <p style="color: #333; line-height: 1.6;">
                            Bạn đã yêu cầu đặt lại mật khẩu cho tài khoản FINORA. Sử dụng mã sau để xác nhận:
                        </p>
                        <div style="text-align: center; margin: 25px 0;">
                            <div style="display: inline-block; background: #f5f5f5; padding: 15px 40px; border-radius: 8px; border: 2px dashed #1a73e8;">
                                <span style="font-size: 32px; font-weight: bold; letter-spacing: 8px; color: #1a73e8;">%s</span>
                            </div>
                        </div>
                        <p style="color: #333; line-height: 1.6;">
                            Mã này có hiệu lực trong <strong>5 phút</strong>. Không chia sẻ mã này cho bất kỳ ai.
                        </p>
                        <p style="color: #757575; font-size: 13px; margin-top: 30px; border-top: 1px solid #e0e0e0; padding-top: 15px;">
                            Nếu bạn không yêu cầu đặt lại mật khẩu, vui lòng bỏ qua email này. Tài khoản của bạn vẫn an toàn.
                        </p>
                    </div>
                </div>
                """.formatted(escapeHtml(otp));

        sendHtmlEmail(toEmail, subject, htmlContent);
    }

    /**
     * Gửi email cảnh báo hoạt động đăng nhập bất thường.
     */
    public void sendSuspiciousActivityAlert(String toEmail, String ipAddress, String reason) {
        String subject = "Cảnh báo hoạt động đăng nhập bất thường — FINORA";
        String htmlContent = """
                <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
                    <div style="background: linear-gradient(135deg, #d32f2f, #b71c1c); padding: 30px; border-radius: 12px 12px 0 0; text-align: center;">
                        <h1 style="color: #ffffff; margin: 0; font-size: 28px;">&#9888; FINORA</h1>
                        <p style="color: #ffcdd2; margin-top: 8px; font-size: 14px;">Cảnh báo bảo mật</p>
                    </div>
                    <div style="background: #ffffff; padding: 30px; border: 1px solid #e0e0e0; border-top: none; border-radius: 0 0 12px 12px;">
                        <h2 style="color: #d32f2f; margin-top: 0;">Phát hiện hoạt động bất thường</h2>
                        <p style="color: #333; line-height: 1.6;">
                            Chúng tôi đã phát hiện hoạt động đăng nhập bất thường trên tài khoản FINORA của bạn:
                        </p>
                        <table style="width: 100%%; border-collapse: collapse; margin: 20px 0;">
                            <tr>
                                <td style="padding: 10px; border-bottom: 1px solid #e0e0e0; color: #757575; width: 120px;">Địa chỉ IP</td>
                                <td style="padding: 10px; border-bottom: 1px solid #e0e0e0; color: #333; font-weight: bold;">%s</td>
                            </tr>
                            <tr>
                                <td style="padding: 10px; border-bottom: 1px solid #e0e0e0; color: #757575;">Lý do</td>
                                <td style="padding: 10px; border-bottom: 1px solid #e0e0e0; color: #333; font-weight: bold;">%s</td>
                            </tr>
                        </table>
                        <p style="color: #333; line-height: 1.6;">
                            Nếu đây không phải bạn, hãy <strong>đổi mật khẩu ngay lập tức</strong> và liên hệ bộ phận hỗ trợ.
                        </p>
                        <p style="color: #757575; font-size: 13px; margin-top: 30px; border-top: 1px solid #e0e0e0; padding-top: 15px;">
                            Email này được gửi tự động bởi hệ thống bảo mật FINORA.
                        </p>
                    </div>
                </div>
                """.formatted(escapeHtml(ipAddress), escapeHtml(reason));

        sendHtmlEmail(toEmail, subject, htmlContent);
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
            log.info("Đã gửi email thành công: subject='{}', to='{}'", subject, toEmail);

        } catch (MessagingException e) {
            log.error("Lỗi gửi email: subject='{}', to='{}', lỗi={}",
                    subject, toEmail, e.getMessage());
        } catch (Exception e) {
            log.error("Lỗi không mong đợi khi gửi email: subject='{}', to='{}'",
                    subject, toEmail, e);
        }
    }

    /**
     * Escape HTML đơn giản để chống XSS trong nội dung email.
     */
    private String escapeHtml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
