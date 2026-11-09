package com.finora.loan.mapper.contract;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.entity.ContractPdfSignature;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.entity.LoanContractDocument;
import com.finora.loan.domain.contract.entity.LoanContractStatusHistory;
import com.finora.loan.domain.contract.enums.SignatureMethod;
import com.finora.loan.domain.contract.enums.SignatureProviderType;
import com.finora.loan.domain.contract.model.ContractPdfSignatureSummary;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.disbursement.entity.DisbursementSaga;
import com.finora.loan.dto.contract.response.LoanContractActionResponse;
import com.finora.loan.dto.contract.response.LoanContractDetailResponse;
import com.finora.loan.dto.contract.response.LoanContractHistoryResponse;
import com.finora.loan.dto.contract.response.LoanContractPdfResponse;
import com.finora.loan.dto.contract.response.LoanContractSignedPdfResponse;
import com.finora.loan.dto.contract.response.LoanContractSummaryResponse;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class LoanContractMapper {

    private final ScheduleCalculationSnapshotMapper scheduleCalculationSnapshotMapper;

    public LoanContractSummaryResponse toSummary(LoanContract contract, LoanApplication application) {
        return new LoanContractSummaryResponse(
                contract.getContractNumber(), application.getApplicationNumber(), contract.getPrincipalAmount(),
                contract.getTermMonths(), contract.getAnnualInterestRate(), contract.getTotalRepayment(),
                contract.getStatus(), contract.getExpiresAt(), contract.getVersion(), contract.getCreatedAt(),
                contract.getContractCode(), application.getApplicationCode(), application.getPurposeCode()
        );
    }

    public LoanContractDetailResponse toDetail(
            LoanContract contract,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            LoanContractDocument pdfDocument,
            DisbursementSaga disbursement,
            SignatureProviderType availableSignatureProvider,
            SignatureMethod availableSignatureMethod,
            List<ContractPdfSignatureSummary> pdfSignatures,
            LoanContractDocument receipt
    ) {
        return new LoanContractDetailResponse(
                contract.getContractNumber(), application.getApplicationNumber(), contract.getPrincipalAmount(),
                contract.getTermMonths(), contract.getAnnualInterestRate(), contract.getRepaymentMethod(),
                contract.getTotalInterest(), contract.getTotalFees(), contract.getTotalPenalties(),
                contract.getTotalRepayment(), contract.getFirstInstallment(), contract.getMaximumInstallment(),
                contract.getExpectedDisbursementDate(), contract.getScheduleResponseHash(),
                scheduleCalculationSnapshotMapper.periods(schedule), contract.getTermsVersion(),
                contract.getDocumentVersion(),
                contract.getDocumentContent(), contract.getDocumentContentType(), contract.getDocumentHash(),
                toPdf(contract, pdfDocument),
                contract.getStatus(), contract.getSignedBy(), contract.getSignedAt(), contract.getSignatureMethod(),
                contract.getSignatureProvider(), contract.getSignatureTransactionId(),
                contract.getSignatureEvidenceHash(),
                contract.getSignatureDocumentId(), contract.getSignatureRequestedAt(),
                availableSignatureProvider, availableSignatureMethod,
                contract.getDeclinedBy(), contract.getDeclinedAt(), contract.getDeclineReasonCode(),
                contract.getDeclineReasonDetail(), contract.getExpiresAt(), contract.getEffectiveAt(),
                disbursement == null ? null : disbursement.getStatus(),
                disbursement == null ? null : disbursement.getPaymentReference(),
                disbursement == null ? null : disbursement.getFineractLoanId(),
                disbursement == null ? null : disbursement.getDisbursedAt(),
                contract.getVersion(), contract.getCreatedAt(), contract.getUpdatedAt(),
                contract.getSignatureProfile(), toSignedPdf(contract, pdfSignatures),
                toPdf(contract, receipt, "receipt"),
                contract.getContractCode(), application.getApplicationCode(), application.getPurposeCode()
        );
    }

    private LoanContractPdfResponse toPdf(LoanContract contract, LoanContractDocument document) {
        return toPdf(contract, document, "document");
    }

    private LoanContractPdfResponse toPdf(LoanContract contract, LoanContractDocument document, String path) {
        if (document == null) {
            return null;
        }
        return new LoanContractPdfResponse(
                document.getArtifactType(), document.getDocumentVersion(), document.getContentType(),
                document.getContentHash(), document.getContentLength(),
                "/loan-contracts/" + contract.getContractNumber() + "/" + path
        );
    }

    /** Bản đầu chuỗi chữ ký PAdES; {@code null} khi chưa bên nào ký số hoặc hợp đồng dùng bằng chứng tách rời. */
    private LoanContractSignedPdfResponse toSignedPdf(
            LoanContract contract,
            List<ContractPdfSignatureSummary> signatures
    ) {
        if (signatures == null || signatures.isEmpty()) {
            return null;
        }
        ContractPdfSignatureSummary latest = signatures.getLast();
        return new LoanContractSignedPdfResponse(
                ContractPdfSignature.SIGNATURE_FORMAT, signatures.size(), latest.contentHash(),
                latest.contentLength(), latest.signedAt(),
                "/loan-contracts/" + contract.getContractNumber() + "/signed-document");
    }

    public LoanContractActionResponse toAction(LoanContract contract) {
        String actor = contract.getSignedBy() != null ? contract.getSignedBy() : contract.getDeclinedBy();
        Instant actedAt = contract.getSignedAt() != null ? contract.getSignedAt() : contract.getDeclinedAt();
        return new LoanContractActionResponse(
                contract.getContractNumber(), contract.getStatus(), contract.getVersion(),
                contract.getDocumentHash(), contract.getSignatureProvider(), contract.getSignatureMethod(),
                contract.getSignatureTransactionId(), contract.getSignatureEvidenceHash(), actor, actedAt
        );
    }

    public LoanContractHistoryResponse toHistory(LoanContractStatusHistory history) {
        return new LoanContractHistoryResponse(
                history.getId(), history.getFromStatus(), history.getToStatus(), history.getReasonCode(),
                history.getActorType(), history.getActorId(), history.getOccurredAt()
        );
    }

}
