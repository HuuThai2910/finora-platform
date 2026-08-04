package com.finora.loan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.finora.loan", "com.finora.common"})
@EnableScheduling
public class FinoraLoanApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinoraLoanApplication.class, args);
    }
}
