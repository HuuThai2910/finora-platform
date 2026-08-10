package com.finora.loan.repository.scoring;

import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditScoringAssessmentRepository extends JpaRepository<CreditScoringAssessment, Long> {

    Optional<CreditScoringAssessment> findByLogicalScoringKey(String logicalScoringKey);

    Optional<CreditScoringAssessment> findByIdAndApplicationId(Long id, Long applicationId);

    Page<CreditScoringAssessment> findByApplicationId(Long applicationId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select assessment from CreditScoringAssessment assessment where assessment.id = :id")
    Optional<CreditScoringAssessment> findByIdForUpdate(@Param("id") Long id);

    /** PENDING, retry đến hạn hoặc PROCESSING mất lease đều có thể được worker tiếp quản an toàn. */
    @Query("""
            select assessment.id from CreditScoringAssessment assessment
            where assessment.status = :pending
               or (assessment.status = :retryPending and assessment.nextRetryAt <= :now)
               or (assessment.status = :processing and assessment.updatedAt <= :staleBefore)
            order by assessment.updatedAt asc, assessment.id asc
            """)
    List<Long> findDueIds(
            @Param("pending") CreditAssessmentStatus pending,
            @Param("retryPending") CreditAssessmentStatus retryPending,
            @Param("processing") CreditAssessmentStatus processing,
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            Pageable pageable
    );
}
