package com.finora.loan.service.application;

/**
 * Worker định kỳ chuyển các hồ sơ quá hạn xác nhận điều khoản sang hết hạn (tắt bằng
 * {@code finora.loan.contract.expiry-worker-enabled=false}).
 */
public interface LoanTermsConfirmationExpiryWorker {

    /** Một vòng: xử lý lần lượt hồ sơ đến hạn, hồ sơ lỗi không chặn hồ sơ sau. */
    void expireDueTermsConfirmations();
}
