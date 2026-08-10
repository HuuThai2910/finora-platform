package com.finora.loan.repository;

import com.finora.loan.domain.ScheduleCalculationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScheduleCalculationSnapshotRepository extends JpaRepository<ScheduleCalculationSnapshot, Long> {

    Optional<ScheduleCalculationSnapshot> findByApplicationId(Long applicationId);
}
