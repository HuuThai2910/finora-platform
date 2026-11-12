package com.finora.investment.service.orderbook;

import com.finora.investment.dto.response.OrderBookStreamTicketResponse;

import java.util.Optional;

/**
 * Vé bắt tay WebSocket sổ lệnh.
 *
 * <p>Trình duyệt không gắn được header {@code Authorization} vào WebSocket, còn web chạy trên Vercel phải nối thẳng
 * domain VM nên cookie đăng nhập (gắn domain Vercel) không đi theo. Vì vậy client đã đăng nhập xin vé qua REST (qua
 * Gateway như mọi API Investment), rồi đưa vé vào query lúc bắt tay. Vé ngắn hạn, dùng một lần, gắn với một sổ.</p>
 */
public interface OrderBookStreamTicketService {

    /** Đường dẫn bắt tay WebSocket của một sổ; {@code *} là mã khoản niêm yết. */
    String PATH_PATTERN = "/ws/order-books/*";

    /**
     * Cấp vé cho người đang đăng nhập xem sổ {@code listingId}. Quyền như đọc ảnh sổ qua REST: người dùng đã đăng
     * nhập; tài khoản nhân viên cần quyền Xem "Chợ chuyển nhượng".
     */
    OrderBookStreamTicketResponse issue(Long listingId);

    /**
     * Dùng vé lúc bắt tay. Vé bị gỡ ngay cả khi sai sổ hay đã hết hạn, nên không thử lại được.
     *
     * @return người giữ vé nếu vé hợp lệ cho đúng sổ; rỗng nếu vé không tồn tại, đã dùng, hết hạn hoặc khác sổ
     */
    Optional<StreamTicket> redeem(String ticket, Long listingId);

    /** Dọn vé quá hạn chưa ai dùng. */
    void purgeExpired();

    /** Thông tin còn lại sau khi đổi vé, gắn vào phiên WebSocket để ghi log. */
    record StreamTicket(Long listingId, String userId) {
    }
}
