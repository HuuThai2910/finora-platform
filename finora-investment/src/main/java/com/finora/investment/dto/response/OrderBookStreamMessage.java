package com.finora.investment.dto.response;

/**
 * Tin nhắn văn bản (JSON) server gửi trên kênh WebSocket sổ lệnh.
 *
 * <p>Hiện chỉ có {@code type = "snapshot"}: {@code data} là cả ảnh chụp sổ, giống hệt {@code GET
 * /investments/order-books/{listingId}}. Client bỏ loại tin không biết và giữ ảnh có {@code sequence} lớn nhất.</p>
 */
public record OrderBookStreamMessage(String type, OrderBookSnapshotResponse data) {

    public static final String SNAPSHOT = "snapshot";

    public static OrderBookStreamMessage snapshot(OrderBookSnapshotResponse snapshot) {
        return new OrderBookStreamMessage(SNAPSHOT, snapshot);
    }
}
