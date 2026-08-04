package com.finora.loan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finora.borrower-eligibility")
public record BorrowerEligibilityProperties(
        int minimumAge,
        int maximumAge,
        String policyVersion
) {
    public BorrowerEligibilityProperties {
        minimumAge = minimumAge <= 0 ? 18 : minimumAge;
        maximumAge = maximumAge <= 0 ? 65 : maximumAge;
        policyVersion = policyVersion == null || policyVersion.isBlank()
                ? "BORROWER_ELIGIBILITY_V1"
                : policyVersion.trim();
        if (maximumAge < minimumAge) {
            throw new IllegalArgumentException("Tuổi tối đa phải lớn hơn hoặc bằng tuổi tối thiểu");
        }
    }
}
