package com.finora.loan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.finora.loan", "com.finora.common"})
@EnableFeignClients
public class FinoraLoanApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinoraLoanApplication.class, args);
    }
}
