package com.finora.loan.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Giới hạn retry và lease của bước ghi khoản vay vào Fineract trong saga giải ngân.
 *
 * <p>{@code coreBookingLease} phải lớn hơn tổng thời gian tối đa của các lệnh Fineract trong một lần ghi (tìm/tạo
 * client, tạo loan, approve, disburse); hết lease mà chưa có kết quả thì worker khác được nhận lại saga. Đặt quá nhỏ
 * sẽ làm hai worker cùng gọi Fineract — vẫn an toàn nhờ externalId nhưng gây lỗi trùng không cần thiết.</p>
 */
@ConfigurationProperties(prefix = "finora.loan.disbursement")
public record DisbursementSagaProperties(
        long workerDelay,
        int maxAttempts,
        Duration retryBackoff,
        Duration coreBookingLease
) {
    public DisbursementSagaProperties {
        workerDelay = workerDelay <= 0 ? 5_000 : workerDelay;
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(30) : retryBackoff;
        coreBookingLease = coreBookingLease == null ? Duration.ofMinutes(5) : coreBookingLease;
        if (retryBackoff.isNegative() || retryBackoff.isZero()
                || coreBookingLease.isNegative() || coreBookingLease.isZero()) {
            throw new IllegalArgumentException("Cấu hình retry/lease của saga giải ngân không hợp lệ");
        }
    }
}
