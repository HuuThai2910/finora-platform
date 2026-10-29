package com.finora.investment.service.orderbook;

/**
 * Dọn các trạng thái sổ lệnh không tự kết thúc được.
 */
public interface OrderBookMaintenanceService {

    /**
     * Kết thúc lệnh mua kẹt chờ giữ tiền quá {@code pending-funds-timeout}.
     *
     * <p>Không biết lần giữ đầu đã thành chưa, nên gọi lại {@code hold} cùng mã lệnh: Payment
     * idempotent theo mã nên trả đúng khoản đã giữ nếu có, hoặc giữ mới nếu chưa. Có
     * {@code holdReference} rồi mới từ chối lệnh để worker thanh toán nhả nó — không bỏ sót khoản
     * giữ nào. Payment từ chối hẳn (ví không đủ) nghĩa là chưa từng giữ: từ chối lệnh, không cần nhả.</p>
     *
     * @return số lệnh đã kết thúc
     */
    int expireStalePendingBids(int batchSize);

    /** Huỷ lệnh còn nằm trong sổ của các khoản vay không còn Note giao dịch được. */
    int closeFinishedBooks(int batchSize);
}
