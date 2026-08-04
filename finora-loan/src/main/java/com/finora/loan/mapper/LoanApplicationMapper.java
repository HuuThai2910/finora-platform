package com.finora.loan.mapper;

import com.finora.loan.domain.ApplicantFinancialSnapshot;
import com.finora.loan.domain.LoanApplication;
import com.finora.loan.domain.ScheduleCalculationSnapshot;
import com.finora.loan.dto.response.ApplicantFinancialResponse;
import com.finora.loan.dto.response.LoanApplicationResponse;
import com.finora.loan.dto.response.LoanProductSnapshotResponse;
import com.finora.loan.dto.response.ScheduleCalculationSnapshotResponse;
import org.springframework.stereotype.Component;

@Component
public class LoanApplicationMapper {

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
                calculation(calculationSnapshot),
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

    private ScheduleCalculationSnapshotResponse calculation(ScheduleCalculationSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new ScheduleCalculationSnapshotResponse(
                snapshot.getId(),
                snapshot.getExpectedDisbursementDate(),
                snapshot.getTotalPrincipal(),
                snapshot.getTotalInterest(),
                snapshot.getTotalFees(),
                snapshot.getTotalPenalties(),
                snapshot.getTotalRepayment(),
                snapshot.getFirstInstallment(),
                snapshot.getMaximumInstallment(),
                snapshot.getCalculationPolicyVersion(),
                snapshot.getCalculatedAt()
        );
    }
}
