package com.finora.loan.service;

import com.finora.loan.domain.BorrowerEligibilityCheck;
import com.finora.loan.integration.profile.BorrowerProfileResult;
import com.finora.loan.repository.BorrowerEligibilityCheckRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class BorrowerEligibilityStateService {

    private final BorrowerEligibilityCheckRepository repository;

    /** Provider đã trả xong mới mở transaction ngắn để lưu evidence tối thiểu. */
    @Transactional
    public BorrowerEligibilityCheck persist(
            Long applicationId,
            String requestId,
            String requestHash,
            String responseHash,
            BorrowerProfileResult profile,
            BorrowerEligibilityDecision decision,
            Instant now
    ) {
        BorrowerEligibilityCheck existing = repository.findByRequestId(requestId).orElse(null);
        if (existing != null) {
            return existing;
        }
        return repository.saveAndFlush(BorrowerEligibilityCheck.capture(
                applicationId,
                profile.borrowerId(),
                requestId,
                profile.age(),
                profile.kycStatus(),
                profile.kycReference(),
                profile.kycVersion(),
                profile.incomeVerificationStatus(),
                profile.source(),
                decision.result(),
                decision.reasonCode(),
                requestHash,
                responseHash,
                now
        ));
    }
}
