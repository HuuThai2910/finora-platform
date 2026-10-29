package com.finora.investment.service.orderbook;

import com.finora.investment.dto.request.PlaceBookOrderRequest;
import com.finora.investment.dto.response.BookOrderResponse;

/**
 * Điều phối đặt và huỷ lệnh trên sổ lệnh Notes. Không có transaction ở tầng này: transaction thật
 * nằm ở {@link OrderBookTransactionService}, tách nhau bởi lời gọi Payment.
 */
public interface OrderBookCommandService {

    /**
     * Đặt một lệnh giới hạn. Gửi lại cùng {@code Idempotency-Key} trả về lệnh cũ; lệnh mua còn kẹt
     * ở {@code PENDING_FUNDS} (lần trước Payment không phản hồi) thì làm tiếp từ bước giữ tiền.
     */
    BookOrderResponse placeOrder(Long listingId, String idempotencyKey, PlaceBookOrderRequest request);

    BookOrderResponse cancelOrder(String orderReference);
}
