package com.finora.loan.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finora.loan.reschedule")
public record LoanRescheduleProperties(
        boolean workerEnabled,
        long workerDelay,
        int batchSize,
        int maxAttempts,
        Duration processingLease,
        Duration retryDelay,
        Duration maximumRetryDelay,
        Long fineractReasonId,
        String termsVersion,
        String termsText
) {
    public LoanRescheduleProperties {
        workerDelay = workerDelay <= 0 ? 5_000 : workerDelay;
        batchSize = batchSize <= 0 ? 20 : Math.min(batchSize, 100);
        maxAttempts = maxAttempts <= 0 ? 10 : maxAttempts;
        processingLease = processingLease == null ? Duration.ofMinutes(2) : processingLease;
        retryDelay = retryDelay == null ? Duration.ofSeconds(30) : retryDelay;
        maximumRetryDelay = maximumRetryDelay == null ? Duration.ofMinutes(15) : maximumRetryDelay;
        termsVersion = textOrDefault(termsVersion, "LOAN_RESTRUCTURE_TERMS_V1");
        termsText = textOrDefault(termsText,
                "Tôi xác nhận lịch trả nợ hiện tại vẫn có hiệu lực cho đến khi FINORA phê duyệt "
                        + "và Apache Fineract xác nhận lịch cơ cấu mới.");
        if (processingLease.isZero() || processingLease.isNegative()
                || retryDelay.isZero() || retryDelay.isNegative()
                || maximumRetryDelay.compareTo(retryDelay) < 0) {
            throw new IllegalArgumentException("Cấu hình thời gian cơ cấu khoản vay không hợp lệ");
        }
    }

    public long requireFineractReasonId() {
        if (fineractReasonId == null || fineractReasonId <= 0) {
            throw new IllegalStateException("FINERACT_RESCHEDULE_REASON_ID phải được cấu hình trước khi chạy worker");
        }
        return fineractReasonId;
    }

    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}

