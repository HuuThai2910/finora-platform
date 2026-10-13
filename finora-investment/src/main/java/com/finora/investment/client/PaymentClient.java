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
     * Nhả toàn bộ phần tiền còn đang giữ về ví khả dụng; phần đã thanh toán qua
     * {@link #settleFromHold} không bị ảnh hưởng.
     */
    void release(String holdReference, String orderReference);

    /**
     * Chuyển một phần tiền đang giữ của người mua sang ví người bán, có thu phí — thanh toán một
     * lần khớp trên sổ lệnh.
     *
     * <p>Khác chuyển tiền thường: tiền lấy từ phần <em>đang giữ</em> của lệnh mua chứ không từ số
     * dư khả dụng, nên không thể thiếu tiền — lệnh mua đã giữ đủ từ lúc vào sổ. Một lần giữ có thể
     * được thanh toán nhiều lần (lệnh khớp từng phần); phần còn lại vẫn ở trạng thái giữ cho tới
     * khi {@link #release} nhả nó về ví.</p>
     *
     * <p>Trừ phần giữ đúng {@code amount}; cộng cho người bán {@code amount} trừ
     * {@code platformFee}; phần phí thuộc nền tảng. Investment quyết định con số, Payment chỉ
     * thực hiện — ranh giới theo rule {@code 07-service-boundaries}.</p>
     *
     * <p>Gọi từ worker không có JWT người dùng, nên Payment phải chấp nhận service account của
     * Investment cho thao tác này.</p>
     *
     * @param orderReference    mã lệnh mua đã dùng khi giữ tiền, để Payment đối chiếu đúng khoản giữ
     * @param settlementReference khóa chống trùng phía Payment; gọi lại cùng mã phải trả cùng kết
     *                          quả và chỉ chuyển tiền một lần
     */
    /**
     * Chuyển tiền khả dụng từ ví nhà đầu tư này sang ví nhà đầu tư khác, không phí — dùng để trả
     * lại khoản trả nợ Payment đã chia nhầm cho chủ cũ của Note. Gọi bằng service account; gọi lại
     * cùng {@code transferReference} chỉ chuyển một lần.
     */
    PaymentTransferResult transferBetweenInvestors(
            String fromInvestorId, String toInvestorId, BigDecimal amount, String transferReference);

    PaymentTransferResult settleFromHold(
            String holdReference,
            String orderReference,
            String sellerId,
            BigDecimal amount,
            BigDecimal platformFee,
            String settlementReference);
}
