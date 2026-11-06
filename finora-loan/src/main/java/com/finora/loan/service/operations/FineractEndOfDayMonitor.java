package com.finora.loan.service.operations;

import com.finora.common.observability.OperationalRefreshTask;

/**
 * Giám sát job cuối ngày của Fineract (W3E, D57; D25): đọc định kỳ trạng thái các job bắt buộc và giữ kết quả trong bộ
 * nhớ cho health {@code fineractEndOfDay} và gauge vận hành.
 */
public interface FineractEndOfDayMonitor extends OperationalRefreshTask {

    /**
     * Đọc Fineract và lưu kết quả. Lỗi đọc được ghi lại (health hiện mã lỗi) rồi ném tiếp để luồng vận hành đếm vào
     * {@code finora.operations.task.failures{task=fineract-eod}}; số liệu của lần đọc thành công trước vẫn được giữ.
     */
    void refresh();

    /** Đánh giá tại thời điểm gọi; không gọi Fineract. */
    FineractEndOfDayStatus currentStatus();

    /** Tuổi (giây) lần chạy thành công gần nhất của một job; NaN khi chưa biết hoặc kiểm tra không áp dụng. */
    double lastSuccessAgeSeconds(String shortName);
}
