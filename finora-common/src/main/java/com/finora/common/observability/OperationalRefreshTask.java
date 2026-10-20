package com.finora.common.observability;

import java.time.Duration;

/**
 * Việc kiểm tra vận hành chạy định kỳ trên luồng của {@link OperationalMonitoringScheduler}, ví dụ đọc lịch sử job cuối
 * ngày của Fineract (Loan, D57). Kết quả được giữ trong bộ nhớ để health indicator và gauge đọc ngay, nên health endpoint
 * không phải chờ dependency ngoài.
 *
 * <p>Exception ném ra được scheduler bắt, đếm vào {@code finora.operations.refresh.failures} và log một lần cho mỗi đợt
 * lỗi liên tiếp; task phải tự lưu trạng thái lỗi nếu health cần hiển thị lý do.</p>
 */
public interface OperationalRefreshTask {

    /** Tên ngắn dùng trong log và tag {@code task}; không chứa dữ liệu người dùng. */
    String name();

    /** Khoảng cách giữa hai lần chạy, tính từ lúc lần trước kết thúc. */
    Duration interval();

    void refresh();
}
