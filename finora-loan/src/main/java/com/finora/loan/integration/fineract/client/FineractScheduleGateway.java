package com.finora.loan.integration.fineract.client;

import com.finora.loan.integration.fineract.contract.ScheduleCalculationRequest;
import com.finora.loan.integration.fineract.contract.ScheduleCalculationResult;

/** Biên tính lịch trả; implementation thật gọi Fineract, test thay bằng fake deterministic. */
public interface FineractScheduleGateway {

    /** Tính lịch dự kiến từ Fineract; implementation phải có timeout và không tự thay công thức trong Loan. */
    ScheduleCalculationResult calculateSchedule(ScheduleCalculationRequest request);
}
