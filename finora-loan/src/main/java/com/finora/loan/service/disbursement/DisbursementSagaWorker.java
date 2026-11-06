package com.finora.loan.service.disbursement;

/**
 * Worker định kỳ ghi các saga giải ngân đến hạn vào core.
 */
public interface DisbursementSagaWorker {

    /** Một vòng: xử lý lần lượt các saga đến hạn. */
    void run();
}
