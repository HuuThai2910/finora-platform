package com.finora.blockchain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.finora.blockchain", "com.finora.common"})
public class FinoraBlockchainApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinoraBlockchainApplication.class, args);
    }
}
