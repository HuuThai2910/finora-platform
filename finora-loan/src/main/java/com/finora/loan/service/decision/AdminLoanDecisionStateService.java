package com.finora.loan.service.decision;

import com.finora.loan.dto.decision.request.ApproveLoanApplicationRequest;
import com.finora.loan.dto.decision.request.RejectLoanApplicationRequest;

/**
 * Các bước transaction của quyết định duyệt/từ chối hồ sơ của admin, kèm đối soát idempotency.
 */
public interface AdminLoanDecisionStateService {

    /** Toàn bộ quyết định, Contract và hai history row cùng commit hoặc cùng rollback. */
    AdminDecisionResult approve(
            String applicationNumber,
            String idempotencyKey,
            String requestHash,
            ApproveLoanApplicationRequest request,
            String actorId
    );

    /** Admin reject không tạo Contract; history vẫn commit cùng decision evidence. */
    AdminDecisionResult reject(
            String applicationNumber,
            String idempotencyKey,
            String requestHash,
            RejectLoanApplicationRequest request,
            String actorId
    );

    /**
     * Quyết định đã commit theo {@code Idempotency-Key}; {@code null} nếu chưa có, khác nội
     * dung thì 409 {@code IDEMPOTENCY_KEY_REUSED}.
     */
    AdminDecisionResult findCommittedByKey(
            String applicationNumber,
            String idempotencyKey,
            String requestHash
    );
}
