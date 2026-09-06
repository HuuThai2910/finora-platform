package com.finora.loan.repository.core;

import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.core.ScheduleCalculationPurpose;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleCalculationSnapshotRepository extends JpaRepository<ScheduleCalculationSnapshot, Long> {

    Optional<ScheduleCalculationSnapshot> findByApplicationIdAndPurpose(
            Long applicationId,
            ScheduleCalculationPurpose purpose
    );
}
