package com.finora.investment.domain.orderbook;

/**
 * Lý do lệnh bị huỷ.
 *
 * <ul>
 *   <li>{@code USER} — người đặt tự huỷ.</li>
 *   <li>{@code SELF_TRADE_PREVENTED} — lệnh mới chạm lệnh của chính người đặt; phần còn lại của
 *       lệnh mới bị huỷ để không ai tự mua bán với mình làm giả khối lượng giao dịch.</li>
 *   <li>{@code NOTE_UNAVAILABLE} — Note trong lệnh bán đã tất toán hoặc đổi chủ bằng đường khác,
 *       không còn gì để giao.</li>
 *   <li>{@code DEFAULT_NOT_ACKNOWLEDGED} — khoản vay chuyển nợ xấu sau khi lệnh mua vào sổ, người
 *       mua chưa xác nhận cảnh báo nên lệnh không được khớp vào Note nợ xấu.</li>
 *   <li>{@code BOOK_CLOSED} — mọi Note của khoản vay đã tất toán, sổ không còn gì để giao dịch.</li>
 * </ul>
 */
public enum CancelReason {
    USER,
    SELF_TRADE_PREVENTED,
    NOTE_UNAVAILABLE,
    DEFAULT_NOT_ACKNOWLEDGED,
    BOOK_CLOSED
}
