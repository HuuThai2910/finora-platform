package com.finora.loan.service.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.dto.application.response.ScheduleCalculationSnapshotResponse;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ScheduleCalculationSnapshotMapperTest {

    @Test
    void exposesAllStoredPeriodsWithoutCallingFineractAgain() {
        ScheduleCalculationSnapshot snapshot = ScheduleCalculationSnapshot.submission(
                10L,
                "schedule-request-1",
                20L,
                LocalDate.of(2026, 9, 1),
                "{}",
                """
                        [{
                          "period": 1,
                          "fromDate": "2026-09-01",
                          "dueDate": "2026-10-01",
                          "daysInPeriod": 30,
                          "principal": 4000000,
                          "interest": 500000,
                          "fees": 0,
                          "penalties": 0,
                          "totalDue": 4500000,
                          "outstandingBalance": 46000000
                        }]
                        """,
                new BigDecimal("50000000"),
                new BigDecimal("4000000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("54000000"),
                new BigDecimal("4500000"),
                new BigDecimal("4500000"),
                "a".repeat(64),
                "FINERACT_1_15_SCHEDULE_V1",
                "SYSTEM",
                Instant.parse("2026-08-09T00:00:00Z")
        );
        ScheduleCalculationSnapshotMapper mapper = new ScheduleCalculationSnapshotMapper(
                new ObjectMapper().findAndRegisterModules()
        );

        ScheduleCalculationSnapshotResponse response = mapper.toResponse(snapshot);

        assertThat(response.periods()).hasSize(1);
        assertThat(response.periods().getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(response.periods().getFirst().outstandingBalance()).isEqualByComparingTo("46000000");
    }
}
