package com.finora.loan.service.scoring.impl;

import com.finora.loan.config.BorrowerEligibilityProperties;
import com.finora.loan.domain.scoring.enums.BorrowerKycStatus;
import com.finora.loan.domain.scoring.enums.EligibilityResult;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import com.finora.loan.service.scoring.BorrowerEligibilityDecision;
import com.finora.loan.service.scoring.BorrowerEligibilityPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Chính sách điều kiện người vay trước khi gửi hồ sơ sang AI chấm điểm: trạng thái eKYC và độ tuổi.
 * <p>
 * eKYC đang xử lý thì chờ ({@code RETRY_PENDING}), bị từ chối hoặc hết hạn thì {@code INELIGIBLE}; ngưỡng tuổi lấy từ
 * {@link BorrowerEligibilityProperties}.
 */
@Component
@RequiredArgsConstructor
public class BorrowerEligibilityPolicyImpl implements BorrowerEligibilityPolicy {

    private final BorrowerEligibilityProperties borrowerEligibilityProperties;

    @Override
    public BorrowerEligibilityDecision evaluate(BorrowerProfileResult profile) {
        if (profile.age() == null) {
            return new BorrowerEligibilityDecision(EligibilityResult.INVALID_PROFILE, "BORROWER_AGE_MISSING");
        }
        if (profile.kycStatus() == BorrowerKycStatus.PENDING
                || profile.kycStatus() == BorrowerKycStatus.PROCESSING) {
            return new BorrowerEligibilityDecision(EligibilityResult.RETRY_PENDING, "KYC_NOT_COMPLETED");
        }
        if (profile.kycStatus() == BorrowerKycStatus.REJECTED
                || profile.kycStatus() == BorrowerKycStatus.EXPIRED) {
            return new BorrowerEligibilityDecision(EligibilityResult.INELIGIBLE, "KYC_NOT_VALID");
        }
        if (profile.kycStatus() != BorrowerKycStatus.VERIFIED) {
            return new BorrowerEligibilityDecision(EligibilityResult.INVALID_PROFILE, "KYC_STATUS_UNSUPPORTED");
        }
        if (profile.age() < borrowerEligibilityProperties.minimumAge()
                || profile.age() > borrowerEligibilityProperties.maximumAge()) {
            return new BorrowerEligibilityDecision(EligibilityResult.INELIGIBLE, "BORROWER_AGE_OUT_OF_POLICY");
        }
        return new BorrowerEligibilityDecision(EligibilityResult.ELIGIBLE, null);
    }
}
