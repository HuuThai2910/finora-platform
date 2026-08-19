package com.finora.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình email cho notification service.
 */
@Configuration
@ConfigurationProperties(prefix = "spring.mail")
@Getter
@Setter
public class MailProperties {

    private String username;
}
