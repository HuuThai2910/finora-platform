package com.finora.loan.service.contract;

import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.dto.contract.response.InvestorContractResponse;

public interface InvestorContractService {
    java.util.List<InvestorContractResponse> listMine();
    InvestorContractResponse detail(String contractNumber);
    LoanContractPdfContent document(String contractNumber);
    InvestorContractResponse sign(String contractNumber, String idempotencyKey, SignLoanContractRequest request);
    InvestorContractResponse refreshSignature(String contractNumber);
}
