package com.finora.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình vận hành của báo giá trả trước/tất toán. Tỷ lệ phí, mức tối thiểu và cách chia không còn ở đây: chúng thuộc
 * biểu phí đã công bố trong hợp đồng ({@code EarlySettlementPolicyCatalog}), không được đổi bằng biến môi trường.
 */
@ConfigurationProperties(prefix = "finora.payment.early-settlement")
public record EarlySettlementPolicyProperties(Duration quoteTtl) {
    public EarlySettlementPolicyProperties {
        quoteTtl = quoteTtl == null ? Duration.ofMinutes(15) : quoteTtl;
        if (quoteTtl.isZero() || quoteTtl.isNegative()) {
            throw new IllegalArgumentException("finora.payment.early-settlement.quoteTtl phải dương");
        }
    }
}
