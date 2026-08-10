package com.finora.loan.service;

import com.finora.loan.config.BorrowerEligibilityProperties;
import com.finora.loan.domain.BorrowerKycStatus;
import com.finora.loan.domain.EligibilityResult;
import com.finora.loan.integration.profile.BorrowerProfileResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BorrowerEligibilityPolicy {

    private final BorrowerEligibilityProperties properties;

    /** Policy chỉ quyết định có được gửi AI hay không; không thay thế quyết định tín dụng của admin. */
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
        if (profile.age() < properties.minimumAge() || profile.age() > properties.maximumAge()) {
            return new BorrowerEligibilityDecision(EligibilityResult.INELIGIBLE, "BORROWER_AGE_OUT_OF_POLICY");
        }
        return new BorrowerEligibilityDecision(EligibilityResult.ELIGIBLE, null);
    }
}
