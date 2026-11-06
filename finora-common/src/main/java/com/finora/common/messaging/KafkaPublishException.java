package com.finora.common.messaging;

import java.util.Objects;

/**
 * Lỗi đã phân loại khi gửi event lên Kafka. Outbox relay đọc {@link #code()} và {@link #retryable()} để ghi lỗi rồi
 * thử lại có giới hạn hoặc đưa event vào dead-letter; service có exception outbox riêng thì đổi sang exception đó với
 * cùng mã, thông báo, cờ thử lại và cause.
 */
public class KafkaPublishException extends RuntimeException {

    private final KafkaPublishFailure failure;

    public KafkaPublishException(KafkaPublishFailure failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").message(), cause);
        this.failure = failure;
    }

    /** Loại lỗi, để service thay thông báo riêng nếu cần giữ chữ cũ. */
    public KafkaPublishFailure failure() {
        return failure;
    }

    /** Mã lỗi ghi vào outbox, ví dụ {@code KAFKA_ACK_TIMEOUT}. */
    public String code() {
        return failure.code();
    }

    /** {@code true} thì relay thử lại sau; {@code false} thì đưa vào dead-letter ngay. */
    public boolean retryable() {
        return failure.retryable();
    }
}
