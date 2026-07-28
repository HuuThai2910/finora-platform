package com.finora.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.finora.payment", "com.finora.common"})
public class FinoraPaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinoraPaymentApplication.class, args);
    }
}
