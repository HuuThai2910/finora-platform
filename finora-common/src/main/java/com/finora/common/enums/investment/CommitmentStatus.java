package com.finora.common.enums.investment;

/**
 * Trạng thái phần vốn đã giữ tiền thành công.
 *
 * <p>ACTIVE là đang tính vào tổng gọi vốn. FINALIZED là đã bị Saga giải ngân khóa lại,
 * không thể hủy nữa. CANCELLED là đã nhả tiền về ví nhà đầu tư.</p>
 */
public enum CommitmentStatus {
    ACTIVE,
    FINALIZED,
    CANCELLED
}
