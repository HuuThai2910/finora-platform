package com.finora.payment.service.disbursement;

/**
 * Worker định kỳ của saga giải ngân (bật bằng {@code finora.payment.scheduling-enabled}).
 */
public interface PaymentDisbursementWorker {

    /**
     * Một vòng: xử lý các lệnh giải ngân đến hạn rồi bổ sung bút toán ghi có ví người vay cho lệnh đã xong; lệnh lỗi
     * không chặn lệnh sau.
     */
    void run();
}
