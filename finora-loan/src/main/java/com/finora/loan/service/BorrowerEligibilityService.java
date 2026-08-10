package com.finora.loan.service;

import com.finora.loan.domain.BorrowerEligibilityCheck;
import com.finora.loan.integration.profile.BorrowerProfileProvider;
import com.finora.loan.integration.profile.BorrowerProfileResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BorrowerEligibilityService {

    private final BorrowerProfileProvider profileProvider;
    private final BorrowerEligibilityPolicy policy;
    private final BorrowerEligibilityStateService stateService;
    private final HashingService hashingService;
    private final Clock clock;

    /** Cuộc gọi profile nằm ngoài transaction; kết quả có source/version/hash trước khi được dùng cho scoring. */
    public BorrowerEligibilityCheck evaluate(Long applicationId, String borrowerId) {
        LocalDate asOf = LocalDate.now(clock);
        String requestId = UUID.randomUUID().toString();
        EligibilityProfileRequest request = new EligibilityProfileRequest(applicationId, borrowerId, asOf);
        BorrowerProfileResult profile = profileProvider.getBorrowerProfile(borrowerId, asOf);
        BorrowerEligibilityDecision decision = policy.evaluate(profile);
        Instant now = Instant.now(clock);
        return stateService.persist(
                applicationId,
                requestId,
                hashingService.sha256(request),
                hashingService.sha256(profile),
                profile,
                decision,
                now
        );
    }

    private record EligibilityProfileRequest(Long applicationId, String borrowerId, LocalDate asOf) {
    }
}
