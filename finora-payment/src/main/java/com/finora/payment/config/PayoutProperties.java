package com.finora.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Provider chi tiền ra ngân hàng (R4). Chỉ có {@code mock}; payout thật chờ legal review LEGAL-PAYMENT-01. */
@ConfigurationProperties(prefix = "finora.payment.payout")
public record PayoutProperties(String provider, Mock mock) {

    public PayoutProperties {
        provider = provider == null || provider.isBlank() ? "mock" : provider.trim().toLowerCase();
        mock = mock == null ? new Mock(null, null, null, null) : mock;
    }

    /**
     * @param callbackUrl endpoint callback của chính Payment mà provider giả lập gọi lại
     * @param callbackSecret khóa HMAC chung giữa provider giả lập và Payment (D43)
     * @param callbackDelay provider giả lập báo kết quả sau khoảng này, như provider thật xử lý bất đồng bộ
     * @param callbackEnabled tắt để kiểm nhánh mất callback → tra cứu
     */
    public record Mock(String callbackUrl, String callbackSecret, Duration callbackDelay, Boolean callbackEnabled) {
        public Mock {
            callbackUrl = callbackUrl == null || callbackUrl.isBlank()
                    ? "http://localhost:8082/api/v1/wallets/callbacks/mock-payout" : callbackUrl.trim();
            callbackSecret = callbackSecret == null ? "" : callbackSecret;
            callbackDelay = callbackDelay == null ? Duration.ofSeconds(2) : callbackDelay;
            callbackEnabled = callbackEnabled == null || callbackEnabled;
        }
    }
}
