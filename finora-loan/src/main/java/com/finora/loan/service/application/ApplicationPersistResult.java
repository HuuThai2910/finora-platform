package com.finora.loan.service.application;

import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;

public record ApplicationPersistResult(
        LoanApplication application,
        ScheduleCalculationSnapshot calculationSnapshot
) {
}
