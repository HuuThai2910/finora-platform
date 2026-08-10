package com.finora.loan.service.decision;

import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.decision.request.ApproveLoanApplicationRequest;
import com.finora.loan.dto.decision.request.RejectLoanApplicationRequest;
import com.finora.loan.dto.decision.response.AdminLoanDecisionResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewDetailResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewSummaryResponse;

/** Use case dành cho admin đọc evidence và quyết định hồ sơ; AI chỉ cung cấp đầu vào. */
public interface AdminLoanDecisionService {

    PageResponse<AdminLoanReviewSummaryResponse> listApplications(LoanApplicationStatus status, int page, int size);

    AdminLoanReviewDetailResponse reviewDetail(String applicationNumber);

    AdminLoanDecisionResponse approve(
            String applicationNumber,
            String idempotencyKey,
            ApproveLoanApplicationRequest request
    );

    AdminLoanDecisionResponse reject(
            String applicationNumber,
            String idempotencyKey,
            RejectLoanApplicationRequest request
    );
}
