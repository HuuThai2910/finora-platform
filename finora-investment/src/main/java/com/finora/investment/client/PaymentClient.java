package com.finora.investment.client;

import java.math.BigDecimal;

/**
 * Cổng giao tiếp với finora-payment cho việc giữ và nhả tiền.
 */
public interface PaymentClient {

    /**
     * Giữ tiền trong ví nhà đầu tư cho một lệnh đặt vốn.
     *
     * @param orderReference khóa idempotency phía Payment; gọi lại cùng mã phải trả cùng kết quả
     */
    PaymentHoldResult hold(String investorId, BigDecimal amount, String orderReference);

    /**
     * Nhả tiền đã giữ về ví khả dụng.
     */
    void release(String holdReference, String orderReference);
}
