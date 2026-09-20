package com.finora.investment.service;

import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.OrderResponse;

/**
 * Đặt và hủy lệnh đầu tư — điều phối ba bước quanh lời gọi Payment.
 *
 * <p>Tách interface khỏi cài đặt theo đúng quy ước của finora-user và finora-loan:
 * controller phụ thuộc vào hợp đồng này, không phụ thuộc vào lớp cài đặt.</p>
 */
public interface FundingService {

    /**
     * Đặt một lệnh đầu tư vào khoản vay đang gọi vốn.
     *
     * <p>Gửi lại cùng {@code Idempotency-Key} trả về kết quả của lệnh cũ thay vì tạo lệnh mới,
     * để mạng chập chờn hoặc người dùng bấm hai lần không bị trừ tiền hai lần.</p>
     */
    OrderResponse placeOrder(Long listingId, String idempotencyKey, PlaceOrderRequest request);

    /** Hủy lệnh đã cam kết và nhả tiền về ví. */
    OrderResponse cancelOrder(String orderReference);
}
