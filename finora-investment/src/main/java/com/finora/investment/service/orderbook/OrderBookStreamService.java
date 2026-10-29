package com.finora.investment.service.orderbook;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Đẩy sổ lệnh theo thời gian thực bằng Server-Sent Events.
 */
public interface OrderBookStreamService {

    /** Mở stream cho một sổ; gửi ngay ảnh hiện tại để client không phải gọi REST riêng. */
    SseEmitter subscribe(Long listingId);

    /** Chỉ chạy sau khi transaction đổi sổ đã commit, để client không thấy trạng thái bị rollback. */
    void onBookChanged(OrderBookChangedEvent event);

    /** Gói giữ kết nối, để proxy và trình duyệt không cắt stream khi sổ lâu không đổi. */
    void heartbeat();
}
