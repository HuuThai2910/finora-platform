package com.finora.loan.dto.response;

import com.finora.loan.domain.CreditInformationSource;
import com.finora.loan.domain.EducationLevel;
import com.finora.loan.domain.HomeOwnership;

import java.math.BigDecimal;
import java.time.Instant;

public record ApplicantFinancialResponse(
        BigDecimal declaredMonthlyIncome,
        BigDecimal annualIncomeSnapshot,
        Integer employmentLengthMonths,
        EducationLevel educationLevel,
        HomeOwnership homeOwnership,
        BigDecimal monthlyDebtObligations,
        BigDecimal dtiSnapshot,
        CreditInformationSource informationSource,
        Instant capturedAt
) {
}
