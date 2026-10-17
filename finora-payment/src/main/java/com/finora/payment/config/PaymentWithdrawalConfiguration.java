package com.finora.payment.config;

import com.finora.payment.service.withdrawal.AccountNumberCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({WithdrawalProperties.class, PayoutProperties.class})
public class PaymentWithdrawalConfiguration {

    /** Khóa sai độ dài hay không phải base64 thì Payment dừng khởi động thay vì lưu số tài khoản không mã hóa được. */
    @Bean
    AccountNumberCipher accountNumberCipher(WithdrawalProperties properties) {
        return new AccountNumberCipher(properties.accountEncryptionKey());
    }
}
