package com.finora.loan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Phiên bản snapshot của luồng huy động vốn bắt buộc qua Investment. */
@ConfigurationProperties(prefix = "finora.loan.investment")
public record InvestmentIntegrationProperties(
        int fundingRound,
        int listingVersion
) {
    public InvestmentIntegrationProperties {
        fundingRound = fundingRound == 0 ? 1 : fundingRound;
        listingVersion = listingVersion == 0 ? 1 : listingVersion;
        if (fundingRound < 1 || listingVersion < 1) {
            throw new IllegalArgumentException("fundingRound và listingVersion phải dương");
        }
    }
}
