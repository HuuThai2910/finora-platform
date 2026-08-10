package com.finora.loan.mapper.core;

import com.finora.loan.config.LoanPricingDisclosureProperties;
import com.finora.loan.dto.core.response.RepaymentPreviewResponse;
import com.finora.loan.dto.core.response.SchedulePeriodResponse;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Chuyển contract lịch Fineract đã chuẩn hóa thành response công khai của Loan Service. */
@Component
@RequiredArgsConstructor
public class RepaymentPreviewMapper {

    private final LoanPricingDisclosureProperties disclosureProperties;

    public RepaymentPreviewResponse toResponse(long productId, ScheduleCalculationResult result) {
        return new RepaymentPreviewResponse(
                productId,
                result.amount(),
                result.termMonths(),
                result.annualInterestRate(),
                disclosureProperties.interestRateUnit(),
                result.repaymentMethod(),
                result.estimatedDisbursementDate(),
                result.firstInstallment(),
                result.maximumInstallment(),
                result.totalPrincipal(),
                result.totalInterest(),
                result.totalFees(),
                result.totalPenalties(),
                result.totalRepayment(),
                result.periods().stream()
                        .map(period -> new SchedulePeriodResponse(
                                period.period(),
                                period.fromDate(),
                                period.dueDate(),
                                period.daysInPeriod(),
                                period.principal(),
                                period.interest(),
                                period.fees(),
                                period.penalties(),
                                period.totalDue(),
                                period.outstandingBalance()
                        ))
                        .toList(),
                result.calculationPolicyVersion()
        );
    }
}
