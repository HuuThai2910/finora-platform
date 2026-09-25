package com.finora.blockchain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.finora.blockchain", "com.finora.common"})
@EnableScheduling
public class FinoraBlockchainApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinoraBlockchainApplication.class, args);
    }
}
