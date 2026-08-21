package com.finora.user;

import com.finora.user.config.AuthCookieProperties;
import com.finora.user.config.CryptoProperties;
import com.finora.user.config.KeycloakAdminProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.finora.user", "com.finora.common"})
@EnableFeignClients
@EnableConfigurationProperties({CryptoProperties.class, KeycloakAdminProperties.class, AuthCookieProperties.class})
public class FinoraUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinoraUserApplication.class, args);
    }
}
