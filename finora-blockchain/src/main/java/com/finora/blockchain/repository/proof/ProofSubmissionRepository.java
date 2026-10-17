package com.finora.blockchain.repository.proof;

import com.finora.blockchain.domain.proof.ProofChainCheckStatus;
import com.finora.blockchain.domain.proof.ProofProviderType;
import com.finora.blockchain.domain.proof.ProofSubmission;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import com.finora.blockchain.domain.proof.ProofType;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProofSubmissionRepository extends JpaRepository<ProofSubmission, Long>, ProofSubmissionInsert {

    Optional<ProofSubmission> findBySourceServiceAndSourceEventId(String sourceService, UUID sourceEventId);

    /** Bản ghi cũ nhất của một mốc; dùng cả cho bản REPAYMENT version 1 nằm ngoài unique index. */
    Optional<ProofSubmission> findFirstByProofTypeAndAggregateTypeAndAggregateIdOrderByIdAsc(
            ProofType proofType, String aggregateType, String aggregateId);

    Optional<ProofSubmission> findByProofId(UUID proofId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select proof from ProofSubmission proof where proof.id = :id")
    Optional<ProofSubmission> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select proof from ProofSubmission proof where proof.proofId = :proofId")
    Optional<ProofSubmission> findByProofIdForUpdate(@Param("proofId") UUID proofId);

    /** Worker chỉ lấy proof của provider đang chạy (B50); dùng hai partial index due/processing_lease. */
    @Query("""
            select proof.id from ProofSubmission proof
            where proof.provider = :provider
              and ((proof.status = :pending and proof.availableAt <= :now)
                or (proof.status = :processing and proof.processingStartedAt <= :leaseExpiredBefore))
            order by proof.id
            """)
    List<Long> findDueIds(
            @Param("provider") ProofProviderType provider,
            @Param("pending") ProofSubmissionStatus pending,
            @Param("processing") ProofSubmissionStatus processing,
            @Param("now") Instant now,
            @Param("leaseExpiredBefore") Instant leaseExpiredBefore,
            Pageable pageable
    );

    Page<ProofSubmission> findByLoanApplicationId(Long loanApplicationId, Pageable pageable);

    Page<ProofSubmission> findByAggregateTypeAndAggregateId(String aggregateType, String aggregateId, Pageable pageable);

    Page<ProofSubmission> findByStatus(ProofSubmissionStatus status, Pageable pageable);

    /** Proof Fabric đã xác nhận cần đối chiếu: chưa kiểm hoặc kiểm trước mốc {@code checkedBefore}. */
    @Query("""
            select proof.id from ProofSubmission proof
            where proof.status = :confirmed
              and proof.provider = :provider
              and (proof.chainCheckedAt is null or proof.chainCheckedAt < :checkedBefore)
            order by proof.chainCheckedAt asc nulls first, proof.id asc
            """)
    List<Long> findChainCheckDueIds(
            @Param("confirmed") ProofSubmissionStatus confirmed,
            @Param("provider") ProofProviderType provider,
            @Param("checkedBefore") Instant checkedBefore,
            Pageable pageable
    );

    /**
     * Ghi kết quả đối chiếu bằng update trực tiếp: chỉ chạm hai cột kết quả, không đua optimistic version với
     * worker ghi proof và không bao giờ đổi hash/receipt.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ProofSubmission proof
               set proof.chainCheckStatus = :result, proof.chainCheckedAt = :now, proof.updatedAt = :now
             where proof.id = :id and proof.status = :confirmed and proof.provider = :provider
            """)
    int recordChainCheck(
            @Param("id") Long id,
            @Param("result") ProofChainCheckStatus result,
            @Param("now") Instant now,
            @Param("confirmed") ProofSubmissionStatus confirmed,
            @Param("provider") ProofProviderType provider
    );
}
