package com.finora.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.finora.payment", "com.finora.common"})
@ConfigurationPropertiesScan
@EnableScheduling
public class FinoraPaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinoraPaymentApplication.class, args);
    }
}
