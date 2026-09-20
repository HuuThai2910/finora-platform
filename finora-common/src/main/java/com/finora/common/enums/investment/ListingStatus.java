package com.finora.common.enums.investment;

/**
 * Vòng đời một khoản vay trên sàn gọi vốn.
 *
 * <p>DRAFT là nơi khoản vay rơi vào khi worker tự lấy về từ finora-loan: đã có đủ thông
 * tin khoản vay nhưng chưa ai quyết mệnh giá Note, nên nhà đầu tư chưa nhìn thấy và chưa
 * đặt lệnh được. Quản trị đặt mệnh giá rồi duyệt thì chuyển sang OPEN.</p>
 *
 * <p>OPEN → FULLY_FUNDED khi tổng cam kết chạm mục tiêu; OPEN → CLOSED khi hết hạn
 * mà chưa gọi đủ. CANCELLED dành cho trường hợp finora-loan rút khoản vay khỏi sàn.</p>
 */
public enum ListingStatus {
    DRAFT,
    OPEN,
    FULLY_FUNDED,
    CLOSED,
    CANCELLED
}
