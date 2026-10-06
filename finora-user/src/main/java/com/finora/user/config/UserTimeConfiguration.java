package com.finora.user.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserTimeConfiguration {
    @Bean
    Clock userClock() {
        return Clock.systemUTC();
    }
}
