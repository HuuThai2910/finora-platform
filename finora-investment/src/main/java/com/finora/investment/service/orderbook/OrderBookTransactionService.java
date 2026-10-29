package com.finora.investment.service.orderbook;

import com.finora.investment.domain.orderbook.BookOrder;
import com.finora.investment.domain.orderbook.OrderSide;

/**
 * Các transaction ngắn của sổ lệnh. Mỗi method public là một transaction, không chờ mạng bên trong.
 */
public interface OrderBookTransactionService {

    /** Nội dung lệnh đã kiểm tra hình thức, chuyển từ request vào tầng transaction. */
    record OrderCommand(
            Long listingId,
            String investorId,
            OrderSide side,
            int pricePermille,
            int quantity,
            boolean acknowledgedDefault,
            String idempotencyKey,
            String requestHash) {
    }

    /**
     * Bước 1 của lệnh mua: ghi lệnh {@code PENDING_FUNDS} kèm số tiền cần giữ, chưa vào sổ.
     *
     * <p>Không khóa sổ: lệnh chưa vào sổ thì chưa ảnh hưởng ai. Dư nợ đọc ở đây chỉ để tính số
     * tiền giữ — là trần, không phải số tiền thật của lần khớp.</p>
     */
    BookOrder createPendingBid(OrderCommand command);

    /**
     * Bước 3 của lệnh mua: tiền đã giữ, khóa sổ, cho lệnh vào sổ và khớp.
     *
     * <p>Idempotent: gọi lại khi lệnh đã vào sổ thì trả trạng thái hiện tại.</p>
     */
    BookOrder activateBid(Long orderId, Long listingId, String holdReference);

    BookOrder rejectBid(Long orderId, String reasonCode, String reasonDetail);

    /**
     * Đặt lệnh bán: khóa sổ, chọn và khóa Note, vào sổ, khớp — trong một transaction vì không có
     * bước nào phải chờ mạng.
     */
    BookOrder placeAsk(OrderCommand command);

    /**
     * Huỷ phần chưa khớp của một lệnh.
     *
     * <p>Khóa sổ trước rồi mới đọc lại lệnh, để không huỷ nhầm một lệnh vừa khớp hết ở transaction
     * khác. Huỷ lại lệnh đã huỷ trả kết quả cũ — người dùng có thể bấm hai lần.</p>
     */
    BookOrder cancel(String orderReference, String investorId);

    /**
     * Đối soát lệnh mua kẹt ở {@code PENDING_FUNDS}: lời gọi giữ tiền đối soát đã trả
     * {@code holdReference}, giờ từ chối lệnh và để worker nhả khoản giữ đó.
     *
     * <p>Khóa sổ rồi đọc lại lệnh: nếu người đặt vừa gửi lại cùng khóa và lệnh đã vào sổ thì khoản
     * giữ là của lệnh đang chạy, không được đụng tới.</p>
     *
     * @return {@code true} nếu lệnh vừa bị từ chối ở đây
     */
    boolean expirePendingBid(Long orderId, String holdReference);

    /**
     * Huỷ mọi lệnh còn nằm trong sổ của một đợt gọi vốn không còn Note giao dịch được. Lệnh mua bị
     * huỷ được worker nhả tiền giữ; lệnh bán thì mở khóa Note ngay.
     *
     * @return số lệnh vừa huỷ
     */
    int closeBook(Long listingId);
}
