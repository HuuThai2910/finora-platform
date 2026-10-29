package com.finora.investment.service.orderbook;

/**
 * Thanh toán sau khớp: chuyển tiền đã giữ của người mua sang người bán, rồi nhả phần giữ thừa.
 */
public interface OrderBookSettlementService {

    /** Thanh toán các lần khớp đã tới hạn. Trả số lần thanh toán thành công. */
    int settleDue(int batchSize);

    /**
     * Nhả phần tiền giữ còn lại của lệnh mua đã kết thúc. Chỉ chọn lệnh mà mọi lần khớp đã thanh
     * toán xong — Payment nhả toàn bộ phần còn giữ, nhả sớm thì lần thanh toán sau hết tiền.
     */
    int releaseFinished(int batchSize);
}
