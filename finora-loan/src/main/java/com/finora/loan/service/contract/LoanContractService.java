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

    LoanContractPdfContent document(String contractNumber);

    /** Bản PDF mới nhất của chuỗi chữ ký PAdES (có thể đã có chữ ký các bên cho vay, chưa có chữ ký người vay). */
    LoanContractPdfContent signedDocument(String contractNumber);

    /** Trang biên nhận chữ ký số tách rời, chỉ có sau khi đủ chữ ký. */
    LoanContractPdfContent receipt(String contractNumber);

    LoanContractActionResponse sign(String contractNumber, String idempotencyKey, SignLoanContractRequest request);

    LoanContractActionResponse refreshSignature(String contractNumber);

    /**
     * Worker đối soát phiên SmartCA của borrower khi người dùng không quay lại app (không có security context).
     *
     * @param sessionExpired phiên đã quá hạn: chưa có chữ ký thì đóng phiên để borrower ký lại bằng yêu cầu mới
     */
    void reconcilePadesSignature(String contractNumber, boolean sessionExpired);

    LoanContractActionResponse decline(
            String contractNumber,
            String idempotencyKey,
            DeclineLoanContractRequest request
    );

    PageResponse<LoanContractHistoryResponse> history(String contractNumber, int page, int size);
}
