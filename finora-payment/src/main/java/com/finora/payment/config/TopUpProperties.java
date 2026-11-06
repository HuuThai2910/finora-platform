package com.finora.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "finora.payment.top-up")
public record TopUpProperties(String provider, BigDecimal minimumAmount, BigDecimal maximumAmount) {

    public TopUpProperties {
        provider = provider == null || provider.isBlank() ? "mock" : provider.trim().toLowerCase();
        minimumAmount = minimumAmount == null ? new BigDecimal("10000") : minimumAmount;
        maximumAmount = maximumAmount == null ? new BigDecimal("100000000") : maximumAmount;
        if (minimumAmount.signum() <= 0 || maximumAmount.compareTo(minimumAmount) < 0) {
            throw new IllegalArgumentException("Khoảng tiền nạp không hợp lệ");
        }
    }
}
