package com.finora.investment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phiên bản công bố rủi ro nhà đầu tư phải xác nhận trước lệnh góp vốn sơ cấp (D6, {@code LEGAL-DISCLOSURE-02}:
 * Điều 22 khoản 8 điểm c Nghị định 94/2025 yêu cầu có xác nhận của khách hàng trước khi giao kết).
 *
 * <p>Đổi nội dung công bố thì tăng version; lệnh gửi version cũ bị từ chối để nhà đầu tư đọc lại bản mới.</p>
 */
@ConfigurationProperties(prefix = "finora.investment.risk-disclosure")
public record InvestorRiskDisclosureProperties(String version) {
    public InvestorRiskDisclosureProperties {
        version = version == null || version.isBlank() ? "INVESTOR_RISK_V1" : version.trim();
        if (version.length() > 50) {
            throw new IllegalArgumentException("Version công bố rủi ro tối đa 50 ký tự");
        }
    }
}
