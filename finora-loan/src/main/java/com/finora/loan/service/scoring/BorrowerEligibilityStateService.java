package com.finora.loan.service.scoring;

import com.finora.loan.domain.scoring.entity.BorrowerEligibilityCheck;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;

import java.time.Instant;

/**
 * Lưu kết quả xét điều kiện người vay trong transaction ngắn, idempotent theo {@code requestId}.
 */
public interface BorrowerEligibilityStateService {

    /** Provider đã trả xong mới mở transaction ngắn để lưu evidence tối thiểu. */
    BorrowerEligibilityCheck persist(
            Long applicationId,
            String requestId,
            String requestHash,
            String responseHash,
            BorrowerProfileResult profile,
            BorrowerEligibilityDecision decision,
            String policyVersion,
            Instant now
    );
}
