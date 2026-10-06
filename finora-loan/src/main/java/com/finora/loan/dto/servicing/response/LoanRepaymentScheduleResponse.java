package com.finora.loan.dto.servicing.response;

import com.finora.loan.dto.core.response.SchedulePeriodResponse;
import java.time.Instant;
import java.util.List;

public record LoanRepaymentScheduleResponse(
        LoanServicingSummaryResponse loan,
        List<SchedulePeriodResponse> periods,
        String scheduleSource,
        Instant dataAsOf,
        boolean stale
) {
}

