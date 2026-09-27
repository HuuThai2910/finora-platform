package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.contract.ContractPartyStatus;
import com.finora.loan.domain.contract.LoanContractStatus;
import java.time.Instant;
import java.math.BigDecimal;
import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;

public record InvestorContractResponse(
        String contractNumber,
        LoanContractStatus contractStatus,
        ContractPartyStatus partyStatus,
        long contractVersion,
        String documentHash,
        String pdfDocumentHash,
        BigDecimal investorAmount,
        Integer termMonths,
        int allocationCount,
        int remainingLenderSignatures,
        SignatureProviderType availableSignatureProvider,
        SignatureMethod availableSignatureMethod,
        Instant expiresAt
) {
}
