package com.finora.user;

import com.finora.user.config.ActivityLogProperties;
import com.finora.user.config.AiServiceProperties;
import com.finora.user.config.AuthCookieProperties;
import com.finora.user.config.CicMappingProperties;
import com.finora.user.config.CryptoProperties;
import com.finora.user.config.EkycConsentProperties;
import com.finora.user.config.EkycStorageProperties;
import com.finora.user.config.KeycloakAdminProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.finora.user", "com.finora.common"})
@EnableScheduling
@EnableConfigurationProperties({
        CryptoProperties.class,
        KeycloakAdminProperties.class,
        AuthCookieProperties.class,
        CicMappingProperties.class,
        AiServiceProperties.class,
        EkycConsentProperties.class,
        EkycStorageProperties.class,
        ActivityLogProperties.class
})
public class FinoraUserApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinoraUserApplication.class, args);
    }
}
