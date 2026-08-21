package com.finora.notification.service.impl;

import com.finora.notification.service.EmailTemplateService;
import com.finora.notification.support.HtmlSanitizer;
import org.springframework.stereotype.Service;

/**
 * Triển khai render HTML template cho email — inline style, responsive 600px.
 * <p>
 * Mỗi template có cấu trúc:
 * <ul>
 *   <li>Header gradient với logo FINORA</li>
 *   <li>Body trắng với nội dung chính</li>
 *   <li>Footer ghi chú nhỏ</li>
 * </ul>
 * Tất cả dữ liệu người dùng đều được escape HTML để chống XSS.
 */
@Service
public class EmailTemplateServiceImpl implements EmailTemplateService {

    // ── Thành phần dùng chung ───────────────────────────────────────

    private static final String WRAPPER_OPEN = """
            <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">""";

    private static final String WRAPPER_CLOSE = """
                </div>
            </div>""";

    private static final String HEADER_PRIMARY = """
                <div style="background: linear-gradient(135deg, #1a73e8, #0d47a1); padding: 30px; border-radius: 12px 12px 0 0; text-align: center;">
                    <h1 style="color: #ffffff; margin: 0; font-size: 28px;">FINORA</h1>
                    <p style="color: #bbdefb; margin-top: 8px; font-size: 14px;">%s</p>
                </div>""";

    private static final String HEADER_ALERT = """
                <div style="background: linear-gradient(135deg, #d32f2f, #b71c1c); padding: 30px; border-radius: 12px 12px 0 0; text-align: center;">
                    <h1 style="color: #ffffff; margin: 0; font-size: 28px;">&#9888; FINORA</h1>
                    <p style="color: #ffcdd2; margin-top: 8px; font-size: 14px;">Cảnh báo bảo mật</p>
                </div>""";

    private static final String BODY_OPEN = """
                <div style="background: #ffffff; padding: 30px; border: 1px solid #e0e0e0; border-top: none; border-radius: 0 0 12px 12px;">""";

    private static final String FOOTER = """
                    <p style="color: #757575; font-size: 13px; margin-top: 30px; border-top: 1px solid #e0e0e0; padding-top: 15px;">
                        %s
                    </p>""";

    // ── Templates ───────────────────────────────────────────────────

    @Override
    public String renderWelcomeEmail(String fullName) {
        return WRAPPER_OPEN
                + HEADER_PRIMARY.formatted("Nền tảng cho vay ngang hàng")
                + BODY_OPEN
                + """
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
                        </div>""".formatted(HtmlSanitizer.escape(fullName))
                + FOOTER.formatted("Nếu bạn không tạo tài khoản này, vui lòng bỏ qua email này.")
                + WRAPPER_CLOSE;
    }

    @Override
    public String renderPasswordResetOtp(String otp) {
        return WRAPPER_OPEN
                + HEADER_PRIMARY.formatted("Đặt lại mật khẩu")
                + BODY_OPEN
                + """
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
                        </p>""".formatted(HtmlSanitizer.escape(otp))
                + FOOTER.formatted("Nếu bạn không yêu cầu đặt lại mật khẩu, vui lòng bỏ qua email này. Tài khoản của bạn vẫn an toàn.")
                + WRAPPER_CLOSE;
    }

    @Override
    public String renderSuspiciousActivityAlert(String ipAddress, String reason) {
        return WRAPPER_OPEN
                + HEADER_ALERT
                + BODY_OPEN
                + """
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
                        </p>""".formatted(HtmlSanitizer.escape(ipAddress), HtmlSanitizer.escape(reason))
                + FOOTER.formatted("Email này được gửi tự động bởi hệ thống bảo mật FINORA.")
                + WRAPPER_CLOSE;
    }
}
