package com.finora.payment.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finora.payment.early-settlement")
public record EarlySettlementPolicyProperties(String policyVersion, Duration quoteTtl,
        BigDecimal shortTermFirstBandRate, BigDecimal shortTermSecondBandRate,
        BigDecimal shortTermFirstBandEnd, BigDecimal shortTermFreeAfter,
        BigDecimal mediumTermYearOneRate, BigDecimal mediumTermYearTwoRate,
        BigDecimal minimumFee, BigDecimal investorShareRate) {
    public EarlySettlementPolicyProperties {
        policyVersion = text(policyVersion, "policyVersion");
        quoteTtl = quoteTtl == null ? Duration.ofMinutes(15) : quoteTtl;
        shortTermFirstBandRate = rate(shortTermFirstBandRate, "shortTermFirstBandRate");
        shortTermSecondBandRate = rate(shortTermSecondBandRate, "shortTermSecondBandRate");
        shortTermFirstBandEnd = rate(shortTermFirstBandEnd, "shortTermFirstBandEnd");
        shortTermFreeAfter = rate(shortTermFreeAfter, "shortTermFreeAfter");
        mediumTermYearOneRate = rate(mediumTermYearOneRate, "mediumTermYearOneRate");
        mediumTermYearTwoRate = rate(mediumTermYearTwoRate, "mediumTermYearTwoRate");
        minimumFee = money(minimumFee, "minimumFee");
        investorShareRate = rate(investorShareRate, "investorShareRate");
        if (quoteTtl.isZero() || quoteTtl.isNegative()) {
            throw new IllegalArgumentException("finora.payment.early-settlement.quoteTtl phải dương");
        }
        if (shortTermFirstBandEnd.signum() <= 0
                || shortTermFirstBandEnd.compareTo(shortTermFreeAfter) >= 0
                || shortTermFreeAfter.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("Mốc thời gian phí ngắn hạn phải thỏa 0 < mốc 1 < mốc miễn phí < 1");
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
