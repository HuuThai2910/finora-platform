package com.finora.payment.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentTimeConfiguration {

    @Bean
    Clock paymentClock() {
        return Clock.systemUTC();
    }
}
