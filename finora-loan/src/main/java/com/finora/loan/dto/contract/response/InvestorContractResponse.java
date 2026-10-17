package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.contract.ContractPartyStatus;
import com.finora.loan.domain.contract.ContractSignatureProfile;
import com.finora.loan.domain.contract.LoanContractStatus;
import java.time.Instant;
import java.math.BigDecimal;
import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;

/**
 * {@code pdfDocumentHash} là hash của PDF tải tại {@code /document}: SIGNABLE khi còn chờ ký (client gửi lại khi đồng ý
 * ký), bản ký đủ sau khi hoàn tất. Các field PAdES (R5) là bổ sung, client cũ bỏ qua được.
 */
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
        Instant expiresAt,
        ContractSignatureProfile signatureProfile,
        int pdfSignatureCount,
        String signedPdfDocumentHash,
        String receiptDocumentHash
) {
}
