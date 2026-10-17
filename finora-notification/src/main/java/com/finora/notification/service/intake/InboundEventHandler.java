package com.finora.notification.service.intake;

import java.util.Set;

/**
 * Xử lý một nhóm event nghiệp vụ thành thông báo. Handler chạy trong transaction của
 * {@link NotificationIntakeService}: được cập nhật bảng tra người nhận/lịch nhắc (idempotent, chạy lại không sai) nhưng
 * không gọi mạng và không tự lưu thông báo.
 * <p>
 * Payload sai contract ném {@link IllegalArgumentException} để event vào DLT; không đoán người nhận.
 */
public interface InboundEventHandler {

    Set<EventKey> keys();

    HandlerOutcome handle(InboundEvent event);
}
