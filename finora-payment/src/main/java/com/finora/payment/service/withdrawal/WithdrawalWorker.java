package com.finora.payment.service.withdrawal;

/**
 * Worker định kỳ đưa lệnh rút tới kết quả cuối (bật bằng {@code finora.payment.scheduling-enabled}).
 */
public interface WithdrawalWorker {

    /** Một vòng: xử lý lần lượt các lệnh rút đến hạn; lệnh lỗi không chặn lệnh sau. */
    void run();
}
