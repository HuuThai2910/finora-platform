package com.finora.loan.repository.scoring;

import com.finora.loan.domain.scoring.BorrowerCreditProfile;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BorrowerCreditProfileRepository extends JpaRepository<BorrowerCreditProfile, Long> {

    Optional<BorrowerCreditProfile> findByBorrowerId(String borrowerId);

    /**
     * Khởi tạo projection “chưa có lịch sử” theo cách idempotent. PostgreSQL tự loại
     * lần chèn trùng, nhờ đó hai worker không làm hỏng transaction vì tranh chấp borrower.
     */
    @Modifying
    @Query(value = """
            INSERT INTO borrower_credit_profiles (
                borrower_id, has_internal_credit_history,
                internal_delinquencies_last_2_years, internal_defaulted_loan_count,
                completed_loan_count, source, calculation_policy_version,
                version, created_by, updated_by, created_at, updated_at
            ) VALUES (
                :borrowerId, false, 0, 0, 0, :source,
                :policyVersion, 0, :actorId, :actorId, :now, :now
            )
            ON CONFLICT (borrower_id) DO NOTHING
            """, nativeQuery = true)
    int ensureNoHistory(
            @Param("borrowerId") String borrowerId,
            @Param("source") String source,
            @Param("policyVersion") String policyVersion,
            @Param("actorId") String actorId,
            @Param("now") Instant now
    );
}
