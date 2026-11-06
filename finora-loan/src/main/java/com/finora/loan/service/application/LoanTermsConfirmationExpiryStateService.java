package com.finora.loan.service.application;

import java.util.List;

/**
 * Các bước transaction của worker hết hạn xác nhận điều khoản: lấy batch hồ sơ đến hạn và chuyển từng hồ sơ sang hết
 * hạn.
 */
public interface LoanTermsConfirmationExpiryStateService {

    /** ID hồ sơ đã quá hạn xác nhận điều khoản, tối đa {@code expiry-batch-size} dòng. */
    List<Long> dueIds();

    /**
     * Khóa hồ sơ và chuyển xác nhận điều khoản sang hết hạn.
     *
     * @return {@code false} nếu hồ sơ không còn chờ xác nhận hoặc chưa đến hạn
     */
    boolean expireOne(Long applicationId);
}
