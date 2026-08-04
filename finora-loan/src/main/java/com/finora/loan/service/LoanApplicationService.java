package com.finora.loan.service;

import com.finora.loan.dto.request.CreateLoanApplicationRequest;
import com.finora.loan.dto.request.WithdrawLoanApplicationRequest;
import com.finora.loan.dto.response.LoanApplicationHistoryResponse;
import com.finora.loan.dto.response.LoanApplicationResponse;
import com.finora.loan.dto.response.LoanPurposeResponse;
import com.finora.loan.dto.response.PageResponse;

import java.util.List;

/** Hợp đồng nghiệp vụ cho luồng nộp và theo dõi hồ sơ vay của borrower. */
public interface LoanApplicationService {

    LoanApplicationResponse submit(CreateLoanApplicationRequest request, String idempotencyKey);

    LoanApplicationResponse withdraw(String applicationNumber, WithdrawLoanApplicationRequest request);

    LoanApplicationResponse getMine(String applicationNumber);

    PageResponse<LoanApplicationResponse> listMine(int page, int size);

    PageResponse<LoanApplicationHistoryResponse> history(String applicationNumber, int page, int size);

    List<LoanPurposeResponse> purposes();
}
