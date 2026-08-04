package com.finora.loan.service;

import com.finora.loan.domain.LoanApplication;
import com.finora.loan.domain.ScheduleCalculationSnapshot;

public record ApplicationPersistResult(
        LoanApplication application,
        ScheduleCalculationSnapshot calculationSnapshot
) {
}
