package com.finora.loan.service.servicing;

/**
 * Worker định kỳ đồng bộ servicing từ Fineract (tắt bằng {@code finora.loan.servicing-sync.enabled=false}).
 */
public interface LoanServicingSyncWorker {

    /** Một vòng: đồng bộ tối đa 20 khoản vay, khoản lỗi chỉ được ghi log. */
    void run();
}
