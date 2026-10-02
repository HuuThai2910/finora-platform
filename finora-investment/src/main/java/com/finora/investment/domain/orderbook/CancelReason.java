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
 * </ul>
 */
public enum CancelReason {
    USER,
    SELF_TRADE_PREVENTED,
    NOTE_UNAVAILABLE
}
