package com.finora.loan.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties({
        LoanPricingDisclosureProperties.class,
        LoanContractProperties.class
})
public class LoanTimeConfig {

    @Bean
    Clock loanClock() {
        return Clock.systemUTC();
    }
}
