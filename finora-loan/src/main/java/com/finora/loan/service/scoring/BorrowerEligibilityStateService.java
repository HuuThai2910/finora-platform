package com.finora.loan.service.scoring;

import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.IncomeVerificationStatus;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import com.finora.loan.repository.scoring.BorrowerEligibilityCheckRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            String policyVersion,
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
                policyVersion,
                requestHash,
                responseHash,
                now
        ));
    }
}
