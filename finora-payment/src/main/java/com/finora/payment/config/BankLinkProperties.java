package com.finora.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Liên kết tài khoản ngân hàng (BL-001).
 *
 * @param provider         {@code mock} (ngân hàng giả lập, mặc định); provider thật cài {@code BankAccountProvider}
 * @param maxAccounts      số tài khoản đang liên kết tối đa của một người
 * @param otpTtl           thời gian sống của một mã OTP
 * @param otpMaxAttempts   số lần nhập sai trước khi khóa yêu cầu
 * @param otpMaxResends    số lần xin gửi lại mã cho một yêu cầu
 * @param otpResendCooldown khoảng chờ tối thiểu giữa hai lần gửi mã
 */
@ConfigurationProperties(prefix = "finora.payment.bank-link")
public record BankLinkProperties(String provider, int maxAccounts, Duration otpTtl, int otpMaxAttempts,
        int otpMaxResends, Duration otpResendCooldown) {

    public BankLinkProperties {
        provider = provider == null || provider.isBlank() ? "mock" : provider.trim();
        maxAccounts = maxAccounts <= 0 ? 5 : maxAccounts;
        otpTtl = otpTtl == null ? Duration.ofMinutes(5) : otpTtl;
        otpMaxAttempts = otpMaxAttempts <= 0 ? 5 : otpMaxAttempts;
        otpMaxResends = otpMaxResends <= 0 ? 3 : otpMaxResends;
        otpResendCooldown = otpResendCooldown == null ? Duration.ofSeconds(60) : otpResendCooldown;
    }
}
