package com.finora.loan.service.restructure;

/**
 * Worker định kỳ chạy các yêu cầu cơ cấu đến hạn trên Fineract (bật bằng {@code finora.loan.reschedule.worker-enabled}).
 */
public interface LoanRescheduleWorker {

    /** Một vòng: xử lý lần lượt các yêu cầu đến hạn. */
    void run();
}
