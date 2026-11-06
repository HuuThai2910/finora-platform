package com.finora.loan.service.scoring;

import com.finora.loan.domain.application.entity.BorrowerKycSnapshot;
import com.finora.loan.domain.scoring.entity.BorrowerEligibilityCheck;

/**
 * Xét điều kiện người vay của một hồ sơ và lưu kết quả kèm nguồn, phiên bản policy và hash trước khi chấm điểm.
 */
public interface BorrowerEligibilityService {

    /**
     * Xét điều kiện từ snapshot eKYC chụp lúc nộp (W2-KYC); kết quả có source/version/hash trước khi dùng cho scoring.
     *
     * <p>Hồ sơ nộp trước khi có snapshot: provider đọc được ở nền (mock) thì đọc như cũ; provider cần token người vay
     * thì ghi {@code INVALID_PROFILE/KYC_SNAPSHOT_MISSING} để admin xem xét, không đoán người vay đã xác minh.</p>
     */
    BorrowerEligibilityCheck evaluate(Long applicationId, String borrowerId, BorrowerKycSnapshot snapshot);
}
