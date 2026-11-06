package com.finora.loan.service.scoring.impl;

import com.finora.loan.config.BorrowerEligibilityProperties;
import com.finora.loan.domain.application.entity.BorrowerKycSnapshot;
import com.finora.loan.domain.scoring.entity.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.enums.BorrowerKycStatus;
import com.finora.loan.domain.scoring.enums.BorrowerProfileSource;
import com.finora.loan.domain.scoring.enums.EligibilityResult;
import com.finora.loan.domain.scoring.enums.IncomeVerificationStatus;
import com.finora.loan.integration.profile.client.BorrowerProfileProvider;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import com.finora.loan.service.application.BorrowerKycSnapshots;
import com.finora.loan.service.scoring.BorrowerEligibilityDecision;
import com.finora.loan.service.scoring.BorrowerEligibilityPolicy;
import com.finora.loan.service.scoring.BorrowerEligibilityService;
import com.finora.loan.service.scoring.BorrowerEligibilityStateService;
import com.finora.loan.util.HashingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Xét điều kiện người vay của một hồ sơ và lưu kết quả kèm nguồn, phiên bản policy và hash trước khi chấm điểm.
 * <p>
 * Dùng snapshot eKYC chụp lúc nộp; hồ sơ cũ chưa có snapshot chỉ đọc provider khi provider chạy nền được, nếu không thì
 * ghi {@code INVALID_PROFILE/KYC_SNAPSHOT_MISSING} để quản trị xem xét.
 */
@Service
@RequiredArgsConstructor
public class BorrowerEligibilityServiceImpl implements BorrowerEligibilityService {

    private final BorrowerProfileProvider borrowerProfileProvider;
    private final BorrowerEligibilityPolicy borrowerEligibilityPolicy;
    private final BorrowerEligibilityStateService borrowerEligibilityStateService;
    private final BorrowerEligibilityProperties borrowerEligibilityProperties;
    private final HashingService hashingService;
    private final Clock clock;

    @Override
    public BorrowerEligibilityCheck evaluate(Long applicationId, String borrowerId, BorrowerKycSnapshot snapshot) {
        LocalDate asOf = LocalDate.now(clock);
        String requestId = UUID.randomUUID().toString();
        EligibilityProfileRequest request = new EligibilityProfileRequest(applicationId, borrowerId, asOf);
        BorrowerProfileResult profile;
        BorrowerEligibilityDecision decision;
        if (snapshot != null) {
            profile = BorrowerKycSnapshots.toProfile(borrowerId, snapshot);
            decision = borrowerEligibilityPolicy.evaluate(profile);
        } else if (borrowerProfileProvider.supportsBackgroundLookup()) {
            profile = borrowerProfileProvider.getBorrowerProfile(borrowerId, asOf);
            decision = borrowerEligibilityPolicy.evaluate(profile);
        } else {
            profile = new BorrowerProfileResult(borrowerId, null, null, BorrowerKycStatus.PENDING, null, null,
                    IncomeVerificationStatus.NOT_VERIFIED, BorrowerProfileSource.USER_SERVICE, Instant.now(clock));
            decision = new BorrowerEligibilityDecision(EligibilityResult.INVALID_PROFILE, "KYC_SNAPSHOT_MISSING");
        }
        Instant now = Instant.now(clock);
        return borrowerEligibilityStateService.persist(
                applicationId,
                requestId,
                hashingService.sha256(request),
                hashingService.sha256(profile),
                profile,
                decision,
                borrowerEligibilityProperties.policyVersion(),
                now
        );
    }

    private record EligibilityProfileRequest(Long applicationId, String borrowerId, LocalDate asOf) {
    }
}
