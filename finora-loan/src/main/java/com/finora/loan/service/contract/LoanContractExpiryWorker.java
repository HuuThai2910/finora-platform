package com.finora.loan.service.contract;

/**
 * Worker định kỳ chuyển các hợp đồng quá hạn ký sang EXPIRED (tắt bằng
 * {@code finora.loan.contract.expiry-worker-enabled=false}).
 */
public interface LoanContractExpiryWorker {

    /** Scheduled method không có transaction; mỗi ID đi qua proxy state service để commit riêng. */
    void expireDueContracts();
}
