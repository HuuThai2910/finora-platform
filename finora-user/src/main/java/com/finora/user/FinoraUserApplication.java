package com.finora.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.finora.user", "com.finora.common"})
@EnableFeignClients
public class FinoraUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinoraUserApplication.class, args);
    }
}
