package com.finora.investment.service;

/**
 * Đối soát luồng gọi vốn (F04): những trạng thái chỉ kết thúc được khi có ai đó làm tiếp.
 */
public interface FundingMaintenanceService {

    /**
     * Làm tiếp lệnh kẹt {@code PENDING_FUNDS} quá {@code pending-funds-timeout}. Chỉ chạm lệnh đủ cũ
     * để không giành việc với request của người dùng đang chạy dở.
     *
     * @return số lệnh đã có kết quả cuối (cam kết hoặc bị từ chối)
     */
    int resumeStalePendingOrders(int batchSize);

    /** Nhả khoản giữ của lệnh đã kết thúc; Payment idempotent theo mã lệnh nên gọi lại an toàn. */
    int releasePendingHolds(int batchSize);
}
