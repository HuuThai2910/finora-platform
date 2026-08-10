package com.finora.loan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Nội dung công bố giá được quản lý tập trung để Product API và lúc submit dùng cùng một version. */
@ConfigurationProperties(prefix = "finora.loan")
public record LoanPricingDisclosureProperties(
        String pricingDisclosureVersion,
        String interestRateUnit,
        String rateNotice
) {
    public LoanPricingDisclosureProperties {
        pricingDisclosureVersion = requireText(pricingDisclosureVersion, "pricingDisclosureVersion");
        interestRateUnit = requireText(interestRateUnit, "interestRateUnit");
        rateNotice = requireText(rateNotice, "rateNotice");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("finora.loan." + field + " không được để trống");
        }
        return value.trim();
    }
}
