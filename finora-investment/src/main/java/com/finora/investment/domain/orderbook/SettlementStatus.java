package com.finora.investment.domain.orderbook;

/**
 * Trạng thái thanh toán của một lần khớp.
 *
 * <p>{@code PENDING} ngay khi khớp; worker gọi Payment chuyển tiền từ phần đang giữ của người mua
 * sang người bán rồi chuyển {@code SETTLED}. {@code FAILED} chỉ khi Payment từ chối hẳn — tiền đã
 * giữ sẵn nên việc này không được xảy ra; nếu có thì cần đối soát tay, không tự đảo giao dịch.</p>
 */
public enum SettlementStatus {
    PENDING,
    SETTLED,
    FAILED
}
