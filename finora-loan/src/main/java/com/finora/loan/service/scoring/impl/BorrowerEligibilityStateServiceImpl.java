package com.finora.loan.service.scoring.impl;

import com.finora.loan.domain.scoring.entity.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.enums.IncomeVerificationStatus;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import com.finora.loan.repository.scoring.BorrowerEligibilityCheckRepository;
import com.finora.loan.service.scoring.BorrowerEligibilityDecision;
import com.finora.loan.service.scoring.BorrowerEligibilityStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Lưu kết quả xét điều kiện người vay trong transaction ngắn, idempotent theo {@code requestId}.
 * <p>
 * {@code requestId} đã có bản ghi thì trả bản ghi cũ, không ghi đè.
 */
@Service
@RequiredArgsConstructor
public class BorrowerEligibilityStateServiceImpl implements BorrowerEligibilityStateService {

    private final BorrowerEligibilityCheckRepository borrowerEligibilityCheckRepository;

    @Override
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
        BorrowerEligibilityCheck existing = borrowerEligibilityCheckRepository.findByRequestId(requestId).orElse(null);
        if (existing != null) {
            return existing;
        }
        return borrowerEligibilityCheckRepository.saveAndFlush(BorrowerEligibilityCheck.capture(
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
