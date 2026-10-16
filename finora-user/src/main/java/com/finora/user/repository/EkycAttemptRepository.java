package com.finora.user.repository;

import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycAttemptStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Truy vấn bảng {@code ekyc_attempts}. */
public interface EkycAttemptRepository extends JpaRepository<EkycAttempt, Long> {

    Optional<EkycAttempt> findByPublicId(UUID publicId);

    /** Lượt đang mở của một người dùng — index {@code (user_profile_id, created_at DESC)}. */
    List<EkycAttempt> findByUserProfileIdAndStatusIn(Long userProfileId, Collection<EkycAttemptStatus> statuses);

    /** Mọi lượt của người dùng còn giữ bản nháp mã hoá (để xoá khi rút đồng ý). */
    List<EkycAttempt> findByUserProfileIdAndDraftEncryptedIsNotNull(Long userProfileId);

    /** Lượt bỏ dở quá hạn cho worker quét dọn; giới hạn theo trang để mỗi vòng có khối lượng xác định. */
    @Query("""
            select a from EkycAttempt a
            where a.status = :status and a.createdAt < :cutoff
            order by a.createdAt, a.id
            """)
    List<EkycAttempt> findByStatusCreatedBefore(
            @Param("status") EkycAttemptStatus status,
            @Param("cutoff") Instant cutoff,
            Pageable pageable);

    /** Lượt đã có quyết định từ lâu mà còn bản nháp — xoá bản nháp khi hết hạn lưu. */
    @Query("""
            select a from EkycAttempt a
            where a.status in :statuses and a.decidedAt < :cutoff and a.draftEncrypted is not null
            order by a.decidedAt, a.id
            """)
    List<EkycAttempt> findDecidedWithDraftBefore(
            @Param("statuses") Collection<EkycAttemptStatus> statuses,
            @Param("cutoff") Instant cutoff,
            Pageable pageable);

    /**
     * Trang duyệt theo trạng thái: sắp theo thời điểm gửi tăng dần (hồ sơ chờ lâu nhất lên đầu), dùng
     * index {@code (status, submitted_at)}. Join email trong cùng câu để tránh N+1 khi hiển thị danh sách.
     */
    @Query(value = """
            select new com.finora.user.repository.EkycAttemptSummaryRow(a, p.email)
            from EkycAttempt a join UserProfile p on p.id = a.userProfileId
            where a.status = :status
            order by a.submittedAt asc nulls last, a.id asc
            """,
            countQuery = "select count(a) from EkycAttempt a where a.status = :status")
    Page<EkycAttemptSummaryRow> findSummariesByStatus(@Param("status") EkycAttemptStatus status, Pageable pageable);

    /** Mọi trạng thái, mới nhất trước. */
    @Query(value = """
            select new com.finora.user.repository.EkycAttemptSummaryRow(a, p.email)
            from EkycAttempt a join UserProfile p on p.id = a.userProfileId
            order by a.createdAt desc, a.id desc
            """,
            countQuery = "select count(a) from EkycAttempt a")
    Page<EkycAttemptSummaryRow> findAllSummaries(Pageable pageable);
}
