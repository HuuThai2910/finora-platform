package com.finora.blockchain.repository.proof;

import com.finora.blockchain.domain.proof.ProofSubmission;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProofSubmissionRepository extends JpaRepository<ProofSubmission, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO blockchain_proof_submissions (
                proof_id, source_service, source_event_id, aggregate_type, aggregate_id,
                proof_type, payload_hash, payload_version, provider, status,
                attempt_count, available_at, version, created_at, updated_at
            ) VALUES (
                :proofId, :sourceService, :sourceEventId, :aggregateType, :aggregateId,
                :proofType, :payloadHash, :payloadVersion, :provider, 'PENDING',
                0, :now, 0, :now, :now
            )
            ON CONFLICT (source_service, source_event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("proofId") UUID proofId,
            @Param("sourceService") String sourceService,
            @Param("sourceEventId") UUID sourceEventId,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            @Param("proofType") String proofType,
            @Param("payloadHash") String payloadHash,
            @Param("payloadVersion") int payloadVersion,
            @Param("provider") String provider,
            @Param("now") Instant now
    );

    Optional<ProofSubmission> findBySourceServiceAndSourceEventId(String sourceService, UUID sourceEventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select proof from ProofSubmission proof where proof.id = :id")
    Optional<ProofSubmission> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select proof.id from ProofSubmission proof
            where (proof.status = :pending and proof.availableAt <= :now)
               or (proof.status = :processing and proof.processingStartedAt <= :leaseExpiredBefore)
            order by proof.id
            """)
    List<Long> findDueIds(
            @Param("pending") ProofSubmissionStatus pending,
            @Param("processing") ProofSubmissionStatus processing,
            @Param("now") Instant now,
            @Param("leaseExpiredBefore") Instant leaseExpiredBefore,
            Pageable pageable
    );
}
