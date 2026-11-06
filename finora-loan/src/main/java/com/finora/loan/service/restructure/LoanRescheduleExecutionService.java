package com.finora.loan.service.restructure;

import java.util.List;

/**
 * Thực hiện yêu cầu cơ cấu đã được nhà đầu tư đồng ý trên Fineract: tạo yêu cầu đổi lịch, duyệt, rồi cập nhật
 * projection và phát event.
 */
public interface LoanRescheduleExecutionService {

    /** ID yêu cầu đến lượt xử lý (chờ tạo, chờ duyệt, cần đối soát, hoặc đang chạy nhưng mất lease). */
    List<Long> dueIds();

    /** Chạy bước kế tiếp (tạo hoặc duyệt trên Fineract) của một yêu cầu; lỗi Fineract được ghi vào yêu cầu. */
    void execute(Long id);
}
