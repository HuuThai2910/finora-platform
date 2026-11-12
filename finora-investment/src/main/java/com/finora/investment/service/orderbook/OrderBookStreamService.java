package com.finora.investment.service.orderbook;

import org.springframework.web.socket.WebSocketSession;

/**
 * Đẩy sổ lệnh theo thời gian thực qua WebSocket ({@code /ws/order-books/{listingId}}), một chiều server → client.
 */
public interface OrderBookStreamService {

    /**
     * Nhận phiên vừa bắt tay xong cho một sổ và gửi ngay ảnh hiện tại, để client không phải gọi REST riêng.
     * Sổ không tồn tại thì đóng phiên với mã {@code 4404}.
     */
    void subscribe(Long listingId, WebSocketSession session);

    /** Gỡ phiên đã đóng hoặc lỗi đường truyền. */
    void unsubscribe(WebSocketSession session);

    /** Client vừa có tín hiệu còn sống (khung pong hoặc tin nhắn bất kỳ). */
    void touch(WebSocketSession session);

    /** Chỉ chạy sau khi transaction đổi sổ đã commit, để client không thấy trạng thái bị rollback. */
    void onBookChanged(OrderBookChangedEvent event);

    /**
     * Nhịp giữ kết nối: gửi khung ping để proxy không cắt kết nối khi sổ lâu không đổi; đóng phiên quá lâu không
     * phản hồi (phiên chết) và phiên mở quá thời hạn (client lấy vé mới, tức là kiểm lại quyền).
     */
    void heartbeat();
}
