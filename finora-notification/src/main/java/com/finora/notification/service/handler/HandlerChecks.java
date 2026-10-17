package com.finora.notification.service.handler;

import java.util.Collection;

/** Kiểm tra contract chung của payload; vi phạm là lỗi contract nên ném {@link IllegalArgumentException} (DLT). */
final class HandlerChecks {

    private HandlerChecks() {
    }

    static Long loanId(Long loanApplicationId, String eventType) {
        if (loanApplicationId == null || loanApplicationId <= 0) {
            throw new IllegalArgumentException(eventType + " thiếu loanApplicationId");
        }
        return loanApplicationId;
    }

    static String required(String value, String field, String eventType) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(eventType + " thiếu " + field);
        return value.trim();
    }

    static <T> T required(T value, String field, String eventType) {
        if (value == null) throw new IllegalArgumentException(eventType + " thiếu " + field);
        return value;
    }

    static void notEmpty(Collection<?> values, String field, String eventType) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException(eventType + " thiếu " + field);
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Mã nghiệp vụ để app mở đúng khoản vay; giữ quy ước cũ của hộp thư là {@code loanApplicationId}. */
    static String loanReference(Long loanApplicationId) {
        return loanApplicationId.toString();
    }
}
