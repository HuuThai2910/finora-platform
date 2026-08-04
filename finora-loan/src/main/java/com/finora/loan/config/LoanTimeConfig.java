package com.finora.loan.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class LoanTimeConfig {

    @Bean
    Clock loanClock() {
        return Clock.systemUTC();
    }
}
