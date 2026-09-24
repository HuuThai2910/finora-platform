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

    /**
     * Chuyển tiền giữa hai ví nhà đầu tư cho một giao dịch chuyển nhượng Note, có thu phí.
     *
     * <p>Khác {@link #hold}: ở đây tiền rời ví người mua và về ví người bán ngay trong một
     * lời gọi, không qua bước giữ chỗ. Chuyển nhượng trên chợ thứ cấp khớp tức thì — người
     * mua bấm mua là Note đổi chủ — nên không có khoảng thời gian nào cần giữ tiền chờ.</p>
     *
     * <p>Trừ của người mua đúng {@code price}; cộng cho người bán {@code price} trừ
     * {@code platformFee}. Phần phí thuộc nền tảng. Investment quyết định con số, Payment chỉ
     * thực hiện — ranh giới này theo rule {@code 07-service-boundaries}.</p>
     *
     * @param transferReference khóa chống trùng lặp phía Payment; gọi lại cùng mã phải trả
     *                          cùng kết quả và chỉ chuyển tiền một lần
     */
    PaymentTransferResult transfer(
            String buyerId,
            String sellerId,
            BigDecimal price,
            BigDecimal platformFee,
            String transferReference);
}
