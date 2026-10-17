package com.finora.notification.integration.push;

/**
 * Kết quả gửi một thông điệp.
 *
 * @param ticketId  ID ticket của provider khi {@link PushOutcome#ACCEPTED}; dùng để đọc receipt
 * @param errorCode mã lỗi đã lọc (ví dụ {@code DeviceNotRegistered}, {@code EXPO_HTTP_503}); không chứa payload thô
 */
public record PushTicket(PushOutcome outcome, String ticketId, String errorCode) {

    public static PushTicket accepted(String ticketId) {
        return new PushTicket(PushOutcome.ACCEPTED, ticketId, null);
    }

    public static PushTicket failed(PushOutcome outcome, String errorCode) {
        return new PushTicket(outcome, null, errorCode);
    }
}
