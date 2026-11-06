package com.finora.loan.dto.application.response;

import com.finora.loan.domain.application.enums.CreditInformationSource;
import com.finora.loan.domain.application.enums.EducationLevel;
import com.finora.loan.domain.application.enums.HomeOwnership;

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
