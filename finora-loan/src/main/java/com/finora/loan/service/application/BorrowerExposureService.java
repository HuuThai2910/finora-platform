package com.finora.loan.service.application;

import java.math.BigDecimal;

/**
 * Kiểm hạn mức dư nợ khi nộp hồ sơ (D5, {@code LEGAL-LIMIT-01} — policy demo, chưa phải hằng số pháp lý).
 *
 * <p>Dư nợ tính gồm: gốc còn lại của các khoản đang phục vụ, cộng số tiền đề nghị của các hồ sơ còn có thể thành khoản
 * vay (chưa bị từ chối/rút, chưa hết hạn hay hủy gọi vốn, chưa thành khoản vay), cộng hồ sơ mới. Chỉ xét số tiền hồ sơ
 * mới thì người vay có thể nộp nhiều hồ sơ song song để vượt hạn mức.</p>
 */
public interface BorrowerExposureService {

    /**
     * Chặn hồ sơ mới bằng {@code 422 BORROWER_EXPOSURE_LIMIT_EXCEEDED} khi tổng dư nợ, hồ sơ đang xử lý và số
     * tiền đề nghị vượt hạn mức; phải gọi trong transaction ghi hồ sơ.
     */
    void requireWithinLimit(String borrowerId, BigDecimal requestedAmount);
}
