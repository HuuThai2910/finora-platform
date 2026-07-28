package com.finora.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.finora.notification", "com.finora.common"})
public class FinoraNotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinoraNotificationApplication.class, args);
    }
}
