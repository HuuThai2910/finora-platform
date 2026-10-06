package com.finora.payment.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finora.payment.early-settlement")
public record EarlySettlementPolicyProperties(String policyVersion, Duration quoteTtl,
        BigDecimal shortTermFirstHalfRate, BigDecimal longTermRate, BigDecimal minimumFee) {
    public EarlySettlementPolicyProperties {
        policyVersion = text(policyVersion, "policyVersion");
        quoteTtl = quoteTtl == null ? Duration.ofMinutes(15) : quoteTtl;
        shortTermFirstHalfRate = rate(shortTermFirstHalfRate, "shortTermFirstHalfRate");
        longTermRate = rate(longTermRate, "longTermRate");
        minimumFee = money(minimumFee, "minimumFee");
        if (quoteTtl.isZero() || quoteTtl.isNegative()) {
            throw new IllegalArgumentException("finora.payment.early-settlement.quoteTtl phải dương");
        }
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("finora.payment.early-settlement." + field + " không được trống");
        }
        return value.trim();
    }

    private static BigDecimal rate(BigDecimal value, String field) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("finora.payment.early-settlement." + field + " phải trong [0,1]");
        }
        return value;
    }

    private static BigDecimal money(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("finora.payment.early-settlement." + field + " không hợp lệ");
        }
        return value.setScale(2);
    }
}
