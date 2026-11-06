package com.finora.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "finora.cic.mapping")
public class CicMappingProperties {
    private String internalApiKey = "";
    private int batchSize = 20;
    private int maxAttempts = 20;
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration initialRetryDelay = Duration.ofSeconds(30);
    private Duration maxRetryDelay = Duration.ofMinutes(15);
}
