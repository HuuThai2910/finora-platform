package com.finora.payment.service.repayment;

/**
 * Worker định kỳ của luồng trả nợ (bật bằng {@code finora.payment.scheduling-enabled}).
 */
public interface PaymentRepaymentWorker {

    /**
     * Một vòng: ghi các khoản đã thu vào Fineract rồi chia tiền các khoản core đã ghi; khoản lỗi không chặn khoản sau.
     */
    void run();

    /**
     * Một vòng đối soát: khoản ghi Fineract bị treo quá {@code finora.payment.core-posting-stale-after} chuyển sang đối
     * soát, rồi tra cứu Fineract cho các khoản cần đối soát.
     */
    void reconcile();
}
