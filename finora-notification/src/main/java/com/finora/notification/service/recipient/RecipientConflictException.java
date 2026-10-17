package com.finora.notification.service.recipient;

/**
 * Hai event báo hai người vay khác nhau cho cùng một khoản vay. Là {@link IllegalArgumentException} để Kafka error
 * handler đưa thẳng vào DLT thay vì thử lại hoặc gửi cho một trong hai.
 */
public class RecipientConflictException extends IllegalArgumentException {

    public RecipientConflictException(String message) {
        super(message);
    }
}
