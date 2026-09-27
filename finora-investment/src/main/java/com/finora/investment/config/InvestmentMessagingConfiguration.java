package com.finora.investment.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({InvestmentKafkaProperties.class, InvestmentOutboxProperties.class})
public class InvestmentMessagingConfiguration {

    @Bean
    Clock investmentClock() {
        return Clock.systemUTC();
    }
}
