package com.finora.loan.service.contract;

import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.dto.contract.response.InvestorContractResponse;

public interface InvestorContractService {
    java.util.List<InvestorContractResponse> listMine();
    InvestorContractResponse detail(String contractNumber);
    LoanContractPdfContent document(String contractNumber);
    /** Bản PDF mới nhất của chuỗi chữ ký PAdES (có thể đang chờ các bên sau ký tiếp). */
    LoanContractPdfContent signedDocument(String contractNumber);
    /** Trang biên nhận chữ ký số, chỉ có sau khi đủ chữ ký. */
    LoanContractPdfContent receipt(String contractNumber);
    InvestorContractResponse sign(String contractNumber, String idempotencyKey, SignLoanContractRequest request);
    InvestorContractResponse refreshSignature(String contractNumber);

    /**
     * Worker đối soát phiên SmartCA của một nhà đầu tư khi người dùng không quay lại app (không có security context).
     *
     * @param sessionExpired phiên đã quá {@code finora.signature.pades.signing-session-timeout}: chưa có chữ ký thì
     *                       đóng phiên để bên khác ký tiếp
     */
    void reconcilePadesSignature(String contractNumber, String investorId, boolean sessionExpired);
}
