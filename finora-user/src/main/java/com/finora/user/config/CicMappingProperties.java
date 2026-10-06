package com.finora.user.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
