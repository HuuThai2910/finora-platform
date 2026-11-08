package com.finora.loan.mapper.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.dto.application.response.ScheduleCalculationSnapshotResponse;
import com.finora.loan.dto.core.response.SchedulePeriodResponse;
import com.finora.loan.domain.servicing.model.LoanServicingPeriod;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Chuyển snapshot lịch trả đã đóng băng trong Loan DB thành response có kiểu rõ ràng. */
@Component
@RequiredArgsConstructor
public class ScheduleCalculationSnapshotMapper {

    private static final TypeReference<List<SchedulePeriodResponse>> PERIOD_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ScheduleCalculationSnapshotResponse toResponse(ScheduleCalculationSnapshot snapshot) {
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
                periods(snapshot),
                snapshot.getCalculationPolicyVersion(),
                snapshot.getCalculatedAt()
        );
    }

    public List<SchedulePeriodResponse> periods(ScheduleCalculationSnapshot snapshot) {
        return periods(snapshot.getPeriodsSnapshotJson());
    }

    public List<SchedulePeriodResponse> periods(String periodsSnapshotJson) {
        try {
            return List.copyOf(objectMapper.readValue(periodsSnapshotJson, PERIOD_LIST_TYPE));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Schedule periods snapshot trong Loan DB không hợp lệ", exception);
        }
    }

    public String periodsJson(List<LoanServicingPeriod> periods) {
        try {
            return objectMapper.writeValueAsString(periods);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Không thể đóng băng lịch Fineract", exception);
        }
    }
}
