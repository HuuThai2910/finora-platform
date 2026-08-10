package com.finora.loan.mapper.application;

import com.finora.loan.domain.application.ApplicantFinancialSnapshot;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.application.LoanApplicationStatusHistory;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.application.response.ApplicantFinancialResponse;
import com.finora.loan.dto.application.response.LoanApplicationResponse;
import com.finora.loan.dto.application.response.LoanApplicationHistoryResponse;
import com.finora.loan.dto.application.response.LoanProductSnapshotResponse;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import org.springframework.stereotype.Component;

@Component
public class LoanApplicationMapper {

    private final ScheduleCalculationSnapshotMapper scheduleMapper;

    public LoanApplicationMapper(ScheduleCalculationSnapshotMapper scheduleMapper) {
        this.scheduleMapper = scheduleMapper;
    }

    public LoanApplicationHistoryResponse toHistoryResponse(LoanApplicationStatusHistory history) {
        return new LoanApplicationHistoryResponse(
                history.getId(), history.getFromStatus(), history.getToStatus(), history.getReasonCode(),
                history.getReasonDetail(), history.getActorType(), history.getActorId(), history.getCreatedAt());
    }

    public LoanApplicationResponse toResponse(
            LoanApplication application,
            ScheduleCalculationSnapshot calculationSnapshot
    ) {
        return new LoanApplicationResponse(
                application.getId(),
                application.getApplicationNumber(),
                application.getBorrowerId(),
                application.getLoanProductId(),
                application.getRequestedAmount(),
                application.getRequestedTermMonths(),
                application.getPurposeCode(),
                application.getPurposeDetail(),
                financial(application.getFinancialSnapshot()),
                product(application),
                scheduleMapper.toResponse(calculationSnapshot),
                application.getExpectedDisbursementDate(),
                application.getPricingDisclosureVersionSnapshot(),
                application.getPricingDisclosureAcceptedAt(),
                application.getStatus(),
                application.getSubmittedAt(),
                application.getWithdrawnAt(),
                application.getWithdrawalReason(),
                application.getLatestCreditAssessmentId(),
                application.getVersion(),
                application.getCreatedBy(),
                application.getUpdatedBy(),
                application.getCreatedAt(),
                application.getUpdatedAt()
        );
    }

    private ApplicantFinancialResponse financial(ApplicantFinancialSnapshot snapshot) {
        return new ApplicantFinancialResponse(
                snapshot.getDeclaredMonthlyIncome(),
                snapshot.getAnnualIncomeSnapshot(),
                snapshot.getEmploymentLengthMonths(),
                snapshot.getEducationLevel(),
                snapshot.getHomeOwnership(),
                snapshot.getMonthlyDebtObligations(),
                snapshot.getDtiSnapshot(),
                snapshot.getInformationSource(),
                snapshot.getCapturedAt()
        );
    }

    private LoanProductSnapshotResponse product(LoanApplication application) {
        return new LoanProductSnapshotResponse(
                application.getProductCodeSnapshot(),
                application.getProductNameSnapshot(),
                application.getProductConfigurationVersionSnapshot(),
                application.getProductMinAmountSnapshot(),
                application.getProductMaxAmountSnapshot(),
                application.getProductMinTermMonthsSnapshot(),
                application.getProductMaxTermMonthsSnapshot(),
                application.getAnnualInterestRateSnapshot(),
                application.getRepaymentMethodSnapshot(),
                application.getFineractProductIdSnapshot(),
                application.getCoreMappingIdSnapshot(),
                application.getCoreConfigVersionSnapshot()
        );
    }

}
