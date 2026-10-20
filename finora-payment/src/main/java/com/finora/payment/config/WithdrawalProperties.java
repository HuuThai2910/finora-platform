package com.finora.payment.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Policy rút tiền demo (Q4/D19, cấu hình được, không phải trích dẫn luật) và nhịp worker (R4, W3B).
 *
 * @param minimumAmount mỗi lệnh tối thiểu (mặc định 50.000 đ)
 * @param dailyLimit tổng rút trong một ngày nghiệp vụ, không tính lệnh thất bại (mặc định 50.000.000 đ)
 * @param feeAmount phí mỗi lệnh (mặc định 0)
 * @param accountEncryptionKey khóa AES-256 (base64, 32 byte) mã hóa số tài khoản (D44)
 * @param processingLease hạn worker gọi provider; quá hạn mà còn PROCESSING thì chuyển đối soát
 * @param callbackTimeout chờ callback bao lâu trước khi tự tra cứu provider
 * @param reconciliationInterval khoảng giữa hai lần tra cứu
 * @param reconciliationMaxAttempts số lần tra cứu tự động trước khi chờ vận hành
 * @param maxSubmitAttempts số lần gửi lại khi provider khẳng định chưa nhận lệnh
 */
@ConfigurationProperties(prefix = "finora.payment.withdrawal")
public record WithdrawalProperties(BigDecimal minimumAmount, BigDecimal dailyLimit, BigDecimal feeAmount,
        String accountEncryptionKey, Duration processingLease, Duration callbackTimeout,
        Duration reconciliationInterval, int reconciliationMaxAttempts, int maxSubmitAttempts) {

    public WithdrawalProperties {
        minimumAmount = (minimumAmount == null ? new BigDecimal("50000") : minimumAmount).setScale(2);
        dailyLimit = (dailyLimit == null ? new BigDecimal("50000000") : dailyLimit).setScale(2);
        feeAmount = (feeAmount == null ? BigDecimal.ZERO : feeAmount).setScale(2);
        if (minimumAmount.signum() <= 0 || dailyLimit.compareTo(minimumAmount) < 0 || feeAmount.signum() < 0) {
            throw new IllegalArgumentException("Policy rút tiền không hợp lệ");
        }
        processingLease = processingLease == null ? Duration.ofMinutes(2) : processingLease;
        callbackTimeout = callbackTimeout == null ? Duration.ofMinutes(2) : callbackTimeout;
        reconciliationInterval = reconciliationInterval == null ? Duration.ofSeconds(30) : reconciliationInterval;
        reconciliationMaxAttempts = reconciliationMaxAttempts <= 0 ? 10 : reconciliationMaxAttempts;
        maxSubmitAttempts = maxSubmitAttempts <= 0 ? 3 : maxSubmitAttempts;
    }
}
