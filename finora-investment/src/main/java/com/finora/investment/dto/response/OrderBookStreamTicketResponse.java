package com.finora.investment.dto.response;

import java.time.Instant;

/**
 * Vé mở kênh WebSocket của một sổ lệnh. Client nối {@code path} vào địa chỉ WebSocket của môi trường rồi gắn
 * {@code ?ticket=}; vé dùng được một lần, trước {@code expiresAt}, và chỉ cho đúng sổ đã xin.
 *
 * @param ticket    chuỗi ngẫu nhiên, không chứa thông tin người dùng
 * @param path      đường dẫn bắt tay, ví dụ {@code /ws/order-books/12}
 * @param expiresAt quá thời điểm này thì bắt tay bị từ chối, phải xin vé mới
 */
public record OrderBookStreamTicketResponse(String ticket, String path, Instant expiresAt) {
}
