package com.finora.loan.service.scoring;

import com.finora.loan.config.BorrowerEligibilityProperties;
import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.domain.scoring.EligibilityResult;
import com.finora.loan.domain.scoring.IncomeVerificationStatus;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BorrowerEligibilityPolicyTest {

    private final BorrowerEligibilityPolicy policy = new BorrowerEligibilityPolicy(
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
