package com.finora.notification.service.intake;

/** Đọc phần {@code data} của event thành record; lỗi định dạng là lỗi contract (DLT), không phải lỗi tạm thời. */
public interface EventPayloads {

    <T> T read(InboundEvent event, Class<T> type);

    /** Phần {@code data} của event dạng JSON, để lưu ở bảng event chờ. */
    String write(InboundEvent event);

    /**
     * Dựng lại event từ payload đã lưu ở bảng chờ, giữ envelope (ID, loại, version, thời điểm, topic) của
     * {@code template}.
     */
    InboundEvent withData(InboundEvent template, String payloadJson);
}
