package com.finora.loan.service.scoring;

import com.finora.loan.integration.profile.contract.BorrowerProfileResult;

/**
 * Chính sách điều kiện người vay trước khi gửi hồ sơ sang AI chấm điểm: trạng thái eKYC và độ tuổi.
 */
public interface BorrowerEligibilityPolicy {

    /** Policy chỉ quyết định có được gửi AI hay không; không thay thế quyết định tín dụng của admin. */
    BorrowerEligibilityDecision evaluate(BorrowerProfileResult profile);
}
