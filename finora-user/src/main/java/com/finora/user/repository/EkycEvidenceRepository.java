package com.finora.user.repository;

import com.finora.user.domain.EkycAttemptStatus;
import com.finora.user.domain.EkycEvidence;
import com.finora.user.domain.EkycEvidenceStage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Truy vấn bảng {@code ekyc_evidence}.
 * <p>
 * Các câu quét dọn luôn lọc theo tiền tố {@code ekyc/<env>/}: nhiều môi trường có thể dùng chung một
 * database (máy dev và Azure cùng Neon), nên mỗi instance chỉ được xoá object trong kho của chính nó.
 */
public interface EkycEvidenceRepository extends JpaRepository<EkycEvidence, Long> {

    List<EkycEvidence> findByAttemptIdOrderByIdAsc(Long attemptId);

    Optional<EkycEvidence> findByIdAndAttemptId(Long id, Long attemptId);

    List<EkycEvidence> findByAttemptIdInAndDeletedAtIsNull(Collection<Long> attemptIds);

    /** Bằng chứng còn trên kho của một người dùng (trong môi trường hiện tại). */
    @Query("""
            select e from EkycEvidence e
            where e.deletedAt is null and e.objectKey like :prefix
              and e.attemptId in (select a.id from EkycAttempt a where a.userProfileId = :profileId)
            """)
    List<EkycEvidence> findLiveByUserProfileId(@Param("profileId") Long profileId, @Param("prefix") String prefix);

    /** Đánh dấu đã xoá object; chỉ gọi sau khi kho lưu trữ đã xoá thành công. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update EkycEvidence e set e.deletedAt = :now, e.updatedAt = :now
            where e.id in :ids and e.deletedAt is null
            """)
    int markDeleted(@Param("ids") Collection<Long> ids, @Param("now") Instant now);

    /** Đánh dấu đã xoá theo object key (sau khi xoá đồng bộ các object của lượt cũ). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update EkycEvidence e set e.deletedAt = :now, e.updatedAt = :now
            where e.objectKey in :keys and e.deletedAt is null
            """)
    int markDeletedByObjectKeys(@Param("keys") Collection<String> keys, @Param("now") Instant now);

    /** Bằng chứng của lượt đã bỏ mà chưa xoá được object (xoá đồng bộ thất bại lần trước). */
    @Query("""
            select e from EkycEvidence e
            where e.deletedAt is null and e.objectKey like :prefix
              and e.attemptId in (select a.id from EkycAttempt a where a.status = :status)
            order by e.id
            """)
    List<EkycEvidence> findLiveOfAttemptsInStatus(
            @Param("status") EkycAttemptStatus status,
            @Param("prefix") String prefix,
            Pageable pageable);

    /** Bằng chứng quá hạn lưu — dùng index một phần {@code (stage, created_at) WHERE deleted_at IS NULL}. */
    @Query("""
            select e from EkycEvidence e
            where e.deletedAt is null and e.stage = :stage and e.createdAt < :cutoff and e.objectKey like :prefix
            order by e.createdAt, e.id
            """)
    List<EkycEvidence> findLiveByStageCreatedBefore(
            @Param("stage") EkycEvidenceStage stage,
            @Param("cutoff") Instant cutoff,
            @Param("prefix") String prefix,
            Pageable pageable);

    /**
     * Bằng chứng tạo trước một lần rút đồng ý của chính chủ hồ sơ mà vẫn còn trên kho — lần rút đó đã
     * xoá theo tiền tố nhưng thất bại giữa chừng. Bằng chứng tạo sau khi người dùng đồng ý lại không bị chạm.
     */
    @Query("""
            select e from EkycEvidence e, EkycAttempt a
            where a.id = e.attemptId and e.deletedAt is null and e.objectKey like :prefix
              and exists (select c.id from EkycConsent c
                          where c.userProfileId = a.userProfileId
                            and c.withdrawnAt is not null and c.withdrawnAt > e.createdAt)
            order by e.id
            """)
    List<EkycEvidence> findLiveCreatedBeforeWithdrawal(@Param("prefix") String prefix, Pageable pageable);
}
