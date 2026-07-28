package com.finora.investment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.finora.investment", "com.finora.common"})
public class FinoraInvestmentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinoraInvestmentApplication.class, args);
    }
}
