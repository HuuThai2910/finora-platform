package com.finora.loan.mapper.contract;

import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.LoanContractDocument;
import com.finora.loan.domain.contract.LoanContractStatusHistory;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.contract.response.LoanContractActionResponse;
import com.finora.loan.dto.contract.response.LoanContractDetailResponse;
import com.finora.loan.dto.contract.response.LoanContractHistoryResponse;
import com.finora.loan.dto.contract.response.LoanContractPdfResponse;
import com.finora.loan.dto.contract.response.LoanContractSummaryResponse;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class LoanContractMapper {

    private final ScheduleCalculationSnapshotMapper scheduleMapper;

    public LoanContractMapper(ScheduleCalculationSnapshotMapper scheduleMapper) {
        this.scheduleMapper = scheduleMapper;
    }

    public LoanContractSummaryResponse toSummary(LoanContract contract, String applicationNumber) {
        return new LoanContractSummaryResponse(
                contract.getContractNumber(), applicationNumber, contract.getPrincipalAmount(),
                contract.getTermMonths(), contract.getAnnualInterestRate(), contract.getTotalRepayment(),
                contract.getStatus(), contract.getExpiresAt(), contract.getVersion(), contract.getCreatedAt()
        );
    }

    public LoanContractDetailResponse toDetail(
            LoanContract contract,
            LoanApplication application,
            ScheduleCalculationSnapshot schedule,
            LoanContractDocument pdfDocument
    ) {
        return new LoanContractDetailResponse(
                contract.getContractNumber(), application.getApplicationNumber(), contract.getPrincipalAmount(),
                contract.getTermMonths(), contract.getAnnualInterestRate(), contract.getRepaymentMethod(),
                contract.getTotalInterest(), contract.getTotalFees(), contract.getTotalPenalties(),
                contract.getTotalRepayment(), contract.getFirstInstallment(), contract.getMaximumInstallment(),
                contract.getExpectedDisbursementDate(), contract.getScheduleResponseHash(),
                scheduleMapper.periods(schedule), contract.getTermsVersion(), contract.getDocumentVersion(),
                contract.getDocumentContent(), contract.getDocumentContentType(), contract.getDocumentHash(),
                toPdf(contract, pdfDocument),
                contract.getStatus(), contract.getSignedBy(), contract.getSignedAt(), contract.getSignatureMethod(),
                contract.getDeclinedBy(), contract.getDeclinedAt(), contract.getDeclineReasonCode(),
                contract.getDeclineReasonDetail(), contract.getExpiresAt(), contract.getEffectiveAt(),
                contract.getVersion(), contract.getCreatedAt(), contract.getUpdatedAt()
        );
    }

    private LoanContractPdfResponse toPdf(LoanContract contract, LoanContractDocument document) {
        if (document == null) {
            return null;
        }
        return new LoanContractPdfResponse(
                document.getArtifactType(), document.getDocumentVersion(), document.getContentType(),
                document.getContentHash(), document.getContentLength(),
                "/loan-contracts/" + contract.getContractNumber() + "/document"
        );
    }

    public LoanContractActionResponse toAction(LoanContract contract) {
        String actor = contract.getSignedBy() != null ? contract.getSignedBy() : contract.getDeclinedBy();
        Instant actedAt = contract.getSignedAt() != null ? contract.getSignedAt() : contract.getDeclinedAt();
        return new LoanContractActionResponse(
                contract.getContractNumber(), contract.getStatus(), contract.getVersion(),
                contract.getDocumentHash(), actor, actedAt
        );
    }

    public LoanContractHistoryResponse toHistory(LoanContractStatusHistory history) {
        return new LoanContractHistoryResponse(
                history.getId(), history.getFromStatus(), history.getToStatus(), history.getReasonCode(),
                history.getActorType(), history.getActorId(), history.getOccurredAt()
        );
    }

}
