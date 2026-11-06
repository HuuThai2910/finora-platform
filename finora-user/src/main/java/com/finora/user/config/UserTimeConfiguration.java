package com.finora.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class UserTimeConfiguration {
    @Bean
    Clock userClock() {
        return Clock.systemUTC();
    }
}
