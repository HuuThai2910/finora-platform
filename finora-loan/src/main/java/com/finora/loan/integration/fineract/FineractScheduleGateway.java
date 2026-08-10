package com.finora.loan.integration.fineract;

/** Biên tính lịch trả; implementation thật gọi Fineract, test thay bằng fake deterministic. */
public interface FineractScheduleGateway {

    ScheduleCalculationResult calculateSchedule(ScheduleCalculationRequest request);
}
