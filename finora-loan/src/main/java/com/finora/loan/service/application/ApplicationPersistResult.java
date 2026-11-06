package com.finora.loan.service.application;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;

public record ApplicationPersistResult(
        LoanApplication application,
        ScheduleCalculationSnapshot calculationSnapshot
) {
}
