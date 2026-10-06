package com.finora.notification.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationRuntimeConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
