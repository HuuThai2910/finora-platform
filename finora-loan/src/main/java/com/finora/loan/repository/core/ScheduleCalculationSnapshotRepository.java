package com.finora.loan.repository.core;

import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.core.enums.ScheduleCalculationPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScheduleCalculationSnapshotRepository extends JpaRepository<ScheduleCalculationSnapshot, Long> {

    Optional<ScheduleCalculationSnapshot> findByApplicationIdAndPurpose(
            Long applicationId,
            ScheduleCalculationPurpose purpose
    );
}
