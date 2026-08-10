package com.finora.loan.repository.core;

import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleCalculationSnapshotRepository extends JpaRepository<ScheduleCalculationSnapshot, Long> {

    Optional<ScheduleCalculationSnapshot> findByApplicationId(Long applicationId);
}
