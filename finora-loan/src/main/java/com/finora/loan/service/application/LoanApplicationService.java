package com.finora.loan.service.application;

import com.finora.loan.dto.application.request.CreateLoanApplicationRequest;
import com.finora.loan.dto.application.request.WithdrawLoanApplicationRequest;
import com.finora.loan.dto.application.response.LoanApplicationHistoryResponse;
import com.finora.loan.dto.application.response.LoanApplicationResponse;
import com.finora.loan.dto.application.response.LoanPurposeResponse;
import com.finora.loan.dto.common.PageResponse;
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
