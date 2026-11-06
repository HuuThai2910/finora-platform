package com.finora.common.messaging;

/**
 * Các loại lỗi khi gửi event lên Kafka, kèm mã lỗi, thông báo và cờ thử lại được mà outbox relay ghi vào DB
 * ({@code last_error_code}, {@code last_error_detail}). Mã và thông báo giữ đúng bản đã chạy ở Loan; Investment có ba
 * thông báo viết khác và tự đổi lại khi chuyển sang exception của mình.
 */
public enum KafkaPublishFailure {

    /** Payload không phải JSON object (mảng, chuỗi, rỗng...): gửi lại cũng hỏng như cũ. */
    PAYLOAD_NOT_OBJECT("OUTBOX_PAYLOAD_INVALID", "Outbox payload phải là JSON object", false),

    /** Payload không phải JSON hợp lệ, hoặc không ghi được envelope ra JSON. */
    PAYLOAD_UNREADABLE("OUTBOX_PAYLOAD_INVALID", "Không thể tạo Kafka envelope từ outbox payload", false),

    /** Luồng gửi bị ngắt khi đang chờ broker; cờ interrupt đã được đặt lại. */
    INTERRUPTED("KAFKA_PUBLISH_INTERRUPTED", "Luồng publish Kafka bị ngắt", true),

    /** Broker chưa xác nhận trong thời gian chờ; consumer chống trùng theo eventId nên gửi lại là an toàn. */
    ACK_TIMEOUT("KAFKA_ACK_TIMEOUT", "Kafka chưa xác nhận event trong thời gian cho phép", true),

    /** Kafka từ chối vì cấu hình hoặc dữ liệu (xác thực, phân quyền, topic sai, record quá lớn, serialize lỗi). */
    REJECTED("KAFKA_PUBLISH_REJECTED", "Kafka từ chối event do cấu hình hoặc dữ liệu", false),

    /** Lỗi Kafka đánh dấu là tạm thời ({@code RetriableException}). */
    TEMPORARILY_UNAVAILABLE("KAFKA_TEMPORARILY_UNAVAILABLE", "Kafka tạm thời chưa nhận event", true),

    /** Lỗi khác chưa phân loại: coi như tạm thời. */
    FAILED("KAFKA_PUBLISH_FAILED", "Không publish được event sang Kafka", true);

    private final String code;
    private final String message;
    private final boolean retryable;

    KafkaPublishFailure(String code, String message, boolean retryable) {
        this.code = code;
        this.message = message;
        this.retryable = retryable;
    }

    /** Mã lỗi {@code UPPER_SNAKE_CASE} ghi vào outbox. */
    public String code() {
        return code;
    }

    /** Thông báo lỗi tiếng Việt, không chứa payload. */
    public String message() {
        return message;
    }

    /** {@code false} thì relay đưa event thẳng vào dead-letter thay vì thử lại. */
    public boolean retryable() {
        return retryable;
    }
}
