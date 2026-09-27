package com.finora.investment.config;

import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finora.investment.kafka")
public record InvestmentKafkaProperties(
        String fundingRequestedTopic,
        String fullyFundedTopic,
        String investorSignatureRequestedTopic,
        String contractActivatedTopic,
        Duration publishTimeout
) {
    private static final Pattern TOPIC = Pattern.compile("finora\\.[a-z0-9][a-z0-9.-]*");

    public InvestmentKafkaProperties {
        fundingRequestedTopic = requireTopic(fundingRequestedTopic, "fundingRequestedTopic");
        fullyFundedTopic = requireTopic(fullyFundedTopic, "fullyFundedTopic");
        investorSignatureRequestedTopic = requireTopic(
                investorSignatureRequestedTopic, "investorSignatureRequestedTopic");
        contractActivatedTopic = requireTopic(contractActivatedTopic, "contractActivatedTopic");
        publishTimeout = publishTimeout == null ? Duration.ofSeconds(10) : publishTimeout;
        if (publishTimeout.isZero() || publishTimeout.isNegative()
                || publishTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("publishTimeout phải từ trên 0 đến 1 phút");
        }
    }

    private static String requireTopic(String value, String field) {
        if (value == null || !TOPIC.matcher(value).matches() || value.length() > 249) {
            throw new IllegalArgumentException(field + " không đúng chuẩn topic FINORA");
        }
        return value;
    }
}
