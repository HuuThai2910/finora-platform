package com.finora.loan.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hạn mức dư nợ của một người vay trên FINORA (quyết định D5, 2026-10-17).
 *
 * <p>Đây là policy demo cấu hình được, không phải hằng số pháp lý: mức 100 triệu đồng được dẫn lại từ nguồn ngành nhưng
 * chưa đối chiếu được văn bản gốc ({@code LEGAL-LIMIT-01}, trạng thái {@code NEEDS_PRIMARY_SOURCE}).</p>
 */
@ConfigurationProperties(prefix = "finora.loan.borrower-exposure")
public record BorrowerExposureProperties(
        Boolean enabled,
        BigDecimal maxOutstanding
) {
    public BorrowerExposureProperties {
        enabled = enabled == null || enabled;
        maxOutstanding = (maxOutstanding == null ? new BigDecimal("100000000") : maxOutstanding)
                .setScale(2, RoundingMode.UNNECESSARY);
        if (maxOutstanding.signum() <= 0) {
            throw new IllegalArgumentException("Hạn mức dư nợ người vay phải lớn hơn 0");
        }
    }
}
