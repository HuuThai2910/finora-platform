package com.finora.investment.domain.orderbook;

/**
 * Vòng đời một lệnh trên sổ.
 *
 * <pre>
 * PENDING_FUNDS ──giữ tiền xong──▶ OPEN ──khớp một phần──▶ PARTIALLY_FILLED ──khớp hết──▶ FILLED
 *       │                           │                           │
 *       └──Payment từ chối──▶ REJECTED   └──────────huỷ──────────┴──────────▶ CANCELLED
 * </pre>
 *
 * <p>Chỉ lệnh mua đi qua {@code PENDING_FUNDS}; lệnh bán không cần giữ tiền nên vào sổ ngay ở
 * {@code OPEN}. {@code FILLED}, {@code CANCELLED}, {@code REJECTED} là trạng thái cuối.</p>
 */
public enum BookOrderStatus {
    PENDING_FUNDS,
    OPEN,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED;

    /** Lệnh đang nằm trong sổ và còn khớp được. */
    public boolean isResting() {
        return this == OPEN || this == PARTIALLY_FILLED;
    }
}
