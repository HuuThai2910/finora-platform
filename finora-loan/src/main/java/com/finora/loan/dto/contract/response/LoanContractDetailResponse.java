package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.domain.contract.enums.ContractDeclineReasonCode;
import com.finora.loan.domain.contract.enums.ContractSignatureProfile;
import com.finora.loan.domain.contract.enums.LoanContractStatus;
import com.finora.loan.domain.contract.enums.SignatureMethod;
import com.finora.loan.domain.contract.enums.SignatureProviderType;
import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.domain.product.enums.RepaymentMethod;
import com.finora.loan.dto.core.response.SchedulePeriodResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LoanContractDetailResponse(
        String contractNumber,
        String applicationNumber,
        BigDecimal principalAmount,
        Integer termMonths,
        BigDecimal annualInterestRate,
        RepaymentMethod repaymentMethod,
        BigDecimal totalInterest,
        BigDecimal totalFees,
        BigDecimal totalPenalties,
        BigDecimal totalRepayment,
        BigDecimal firstInstallment,
        BigDecimal maximumInstallment,
        LocalDate expectedDisbursementDate,
        String scheduleResponseHash,
        List<SchedulePeriodResponse> schedulePeriods,
        String termsVersion,
        String documentVersion,
        String documentContent,
        String documentContentType,
        String documentHash,
        LoanContractPdfResponse pdfDocument,
        LoanContractStatus status,
        String signedBy,
        Instant signedAt,
        SignatureMethod signatureMethod,
        SignatureProviderType signatureProvider,
        String signatureTransactionId,
        String signatureEvidenceHash,
        String signatureDocumentId,
        Instant signatureRequestedAt,
        SignatureProviderType availableSignatureProvider,
        SignatureMethod availableSignatureMethod,
        String declinedBy,
        Instant declinedAt,
        ContractDeclineReasonCode declineReasonCode,
        String declineReasonDetail,
        Instant expiresAt,
        Instant effectiveAt,
        DisbursementSagaStatus disbursementStatus,
        String paymentReference,
        Long fineractLoanId,
        Instant disbursedAt,
        Long version,
        Instant createdAt,
        Instant updatedAt,
        ContractSignatureProfile signatureProfile,
        LoanContractSignedPdfResponse signedPdfDocument,
        LoanContractPdfResponse receiptDocument,
        /** Mã hiển thị {@code HĐ-yyyyMMdd-XXXX} cho người dùng; {@code contractNumber} vẫn là khóa tra cứu API. */
        String contractCode,
        /** Mã hiển thị {@code HS-...} của hồ sơ gốc. */
        String applicationCode,
        /** Mục đích vay của hồ sơ để màn chi tiết hiện dòng tóm tắt thay cho mã. */
        LoanPurpose purposeCode
) {
}
