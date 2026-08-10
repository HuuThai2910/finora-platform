package com.finora.loan.service.contract;

import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.dto.contract.request.DeclineLoanContractRequest;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.dto.contract.response.LoanContractActionResponse;
import com.finora.loan.dto.contract.response.LoanContractDetailResponse;
import com.finora.loan.dto.contract.response.LoanContractHistoryResponse;
import com.finora.loan.dto.contract.response.LoanContractSummaryResponse;

/** Use case đọc và consent đúng một LoanContract thuộc borrower hiện tại. */
public interface LoanContractService {

    PageResponse<LoanContractSummaryResponse> listMine(int page, int size);

    LoanContractDetailResponse detail(String contractNumber);

    LoanContractActionResponse sign(String contractNumber, String idempotencyKey, SignLoanContractRequest request);

    LoanContractActionResponse decline(
            String contractNumber,
            String idempotencyKey,
            DeclineLoanContractRequest request
    );

    PageResponse<LoanContractHistoryResponse> history(String contractNumber, int page, int size);
}
