package com.finora.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retry, lease và đối soát của lệnh giải ngân.
 *
 * <p>{@code processingLease}: thời gian tối đa một lần gọi provider được coi là đang chạy. Hết lease mà vẫn
 * {@code PROCESSING} nghĩa là worker đã chết giữa chừng; không biết provider đã chuyển tiền hay chưa nên chuyển sang đối
 * soát, không gọi chuyển tiền lại. {@code reconciliationInterval}/{@code reconciliationMaxAttempts}: nhịp và số lần
 * tra cứu provider theo {@code sagaId} trước khi để vận hành quyết định.</p>
 */
@ConfigurationProperties(prefix = "finora.payment.disbursement")
public record PaymentDisbursementProperties(
        String provider,
        int maxAttempts,
        Duration retryBackoff,
        Duration processingLease,
        Duration reconciliationInterval,
        int reconciliationMaxAttempts
) {
    public PaymentDisbursementProperties {
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(30) : retryBackoff;
        processingLease = processingLease == null ? Duration.ofMinutes(2) : processingLease;
        reconciliationInterval = reconciliationInterval == null ? Duration.ofMinutes(1) : reconciliationInterval;
        reconciliationMaxAttempts = reconciliationMaxAttempts <= 0 ? 30 : reconciliationMaxAttempts;
        for (Duration value : new Duration[] {retryBackoff, processingLease, reconciliationInterval}) {
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("Cấu hình thời gian giải ngân Payment không hợp lệ");
            }
        }
    }
}
