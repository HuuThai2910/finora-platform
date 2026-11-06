package com.finora.loan.service.scoring.impl;

import com.finora.loan.config.BorrowerEligibilityProperties;
import com.finora.loan.domain.scoring.enums.BorrowerKycStatus;
import com.finora.loan.domain.scoring.enums.BorrowerProfileSource;
import com.finora.loan.domain.scoring.enums.EligibilityResult;
import com.finora.loan.domain.scoring.enums.IncomeVerificationStatus;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import com.finora.loan.service.scoring.BorrowerEligibilityDecision;
import com.finora.loan.service.scoring.BorrowerEligibilityPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BorrowerEligibilityPolicyImplTest {

    private final BorrowerEligibilityPolicy policy = new BorrowerEligibilityPolicyImpl(
            new BorrowerEligibilityProperties(18, 65, "TEST_POLICY"));

    @Test
    void verifiedBorrowerInAgeRangeIsEligible() {
        assertThat(policy.evaluate(profile(30, BorrowerKycStatus.VERIFIED)).result())
                .isEqualTo(EligibilityResult.ELIGIBLE);
    }

    @Test
    void pendingKycMustWaitInsteadOfBeingAssumedVerified() {
        BorrowerEligibilityDecision decision = policy.evaluate(profile(30, BorrowerKycStatus.PROCESSING));
        assertThat(decision.result()).isEqualTo(EligibilityResult.RETRY_PENDING);
        assertThat(decision.reasonCode()).isEqualTo("KYC_NOT_COMPLETED");
    }

    @Test
    void rejectedKycNeverGoesToAi() {
        assertThat(policy.evaluate(profile(30, BorrowerKycStatus.REJECTED)).result())
                .isEqualTo(EligibilityResult.INELIGIBLE);
    }

    private BorrowerProfileResult profile(Integer age, BorrowerKycStatus status) {
        return new BorrowerProfileResult(
                "BORROWER-001", null, age, status, "KYC-1", "V1",
                IncomeVerificationStatus.NOT_VERIFIED,
                BorrowerProfileSource.MOCK_USER_PROFILE,
                Instant.parse("2026-08-03T00:00:00Z"));
    }
}
