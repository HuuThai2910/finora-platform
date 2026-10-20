package com.finora.loan.service.scoring;

import com.finora.loan.config.BorrowerEligibilityProperties;
import com.finora.loan.domain.application.BorrowerKycSnapshot;
import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.domain.scoring.EligibilityResult;
import com.finora.loan.domain.scoring.IncomeVerificationStatus;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import com.finora.loan.integration.profile.provider.BorrowerProfileProvider;
import com.finora.loan.service.application.BorrowerKycSnapshots;
import com.finora.loan.support.HashingService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BorrowerEligibilityService {

    private final BorrowerProfileProvider profileProvider;
    private final BorrowerEligibilityPolicy policy;
    private final BorrowerEligibilityStateService stateService;
    private final BorrowerEligibilityProperties properties;
    private final HashingService hashingService;
    private final Clock clock;

    /**
     * Xét điều kiện từ snapshot eKYC chụp lúc nộp (W2-KYC); kết quả có source/version/hash trước khi dùng cho scoring.
     *
     * <p>Hồ sơ nộp trước khi có snapshot: provider đọc được ở nền (mock) thì đọc như cũ; provider cần token người vay
     * thì ghi {@code INVALID_PROFILE/KYC_SNAPSHOT_MISSING} để admin xem xét, không đoán người vay đã xác minh.</p>
     */
    public BorrowerEligibilityCheck evaluate(Long applicationId, String borrowerId, BorrowerKycSnapshot snapshot) {
        LocalDate asOf = LocalDate.now(clock);
        String requestId = UUID.randomUUID().toString();
        EligibilityProfileRequest request = new EligibilityProfileRequest(applicationId, borrowerId, asOf);
        BorrowerProfileResult profile;
        BorrowerEligibilityDecision decision;
        if (snapshot != null) {
            profile = BorrowerKycSnapshots.toProfile(borrowerId, snapshot);
            decision = policy.evaluate(profile);
        } else if (profileProvider.supportsBackgroundLookup()) {
            profile = profileProvider.getBorrowerProfile(borrowerId, asOf);
            decision = policy.evaluate(profile);
        } else {
            profile = new BorrowerProfileResult(borrowerId, null, null, BorrowerKycStatus.PENDING, null, null,
                    IncomeVerificationStatus.NOT_VERIFIED, BorrowerProfileSource.USER_SERVICE, Instant.now(clock));
            decision = new BorrowerEligibilityDecision(EligibilityResult.INVALID_PROFILE, "KYC_SNAPSHOT_MISSING");
        }
        Instant now = Instant.now(clock);
        return stateService.persist(
                applicationId,
                requestId,
                hashingService.sha256(request),
                hashingService.sha256(profile),
                profile,
                decision,
                properties.policyVersion(),
                now
        );
    }

    private record EligibilityProfileRequest(Long applicationId, String borrowerId, LocalDate asOf) {
    }
}
