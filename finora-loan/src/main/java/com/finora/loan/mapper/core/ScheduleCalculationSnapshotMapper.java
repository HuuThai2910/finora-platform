package com.finora.loan.mapper.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.application.response.ScheduleCalculationSnapshotResponse;
import com.finora.loan.dto.core.response.SchedulePeriodResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/** Chuyển snapshot lịch trả đã đóng băng trong Loan DB thành response có kiểu rõ ràng. */
@Component
public class ScheduleCalculationSnapshotMapper {

    private static final TypeReference<List<SchedulePeriodResponse>> PERIOD_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public ScheduleCalculationSnapshotMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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
        try {
            // Đọc đúng snapshot đã dùng khi nộp hồ sơ; không gọi lại Fineract vì kết quả có thể đã thay đổi.
            return List.copyOf(objectMapper.readValue(snapshot.getPeriodsSnapshotJson(), PERIOD_LIST_TYPE));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Schedule periods snapshot trong Loan DB không hợp lệ", exception);
        }
    }
}
