package com.finora.investment.service.orderbook;

/**
 * Sự kiện nội bộ trong process: sổ của {@code listingId} vừa thay đổi ở phiên bản {@code sequence}.
 * Không phải Kafka event — chỉ để stream biết cần đẩy ảnh mới cho client đang xem.
 */
public record OrderBookChangedEvent(Long listingId, long sequence) {
}
