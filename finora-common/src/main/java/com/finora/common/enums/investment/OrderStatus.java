package com.finora.common.enums.investment;

/**
 * Trạng thái lệnh đặt vốn theo luồng F04.
 *
 * <p>PENDING_FUNDS là lúc đã tạo lệnh nhưng chưa giữ được tiền ở finora-payment.
 * Chỉ khi Payment giữ tiền thành công và commitment được ghi, lệnh mới sang COMMITTED.</p>
 */
public enum OrderStatus {
    PENDING_FUNDS,
    COMMITTED,
    REJECTED,
    CANCELLED
}
