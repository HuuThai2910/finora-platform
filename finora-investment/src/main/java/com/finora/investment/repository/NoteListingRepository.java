package com.finora.investment.repository;

import com.finora.investment.domain.secondary.NoteListing;
import com.finora.investment.domain.secondary.NoteListingStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoteListingRepository extends JpaRepository<NoteListing, Long> {

    Optional<NoteListing> findByListingReference(String listingReference);

    /**
     * Khóa tin đăng bán trước khi khớp giao dịch.
     *
     * <p>Dùng khóa bi quan vì hai người có thể bấm mua cùng lúc: người vào sau phải đợi rồi
     * đọc lại trạng thái, thay vì cả hai đều thấy tin còn mở. Khóa lạc quan sẽ để một người
     * chạy hết luồng — kể cả lời gọi chuyển tiền — rồi mới vỡ khi ghi.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT nl FROM NoteListing nl WHERE nl.id = :id")
    Optional<NoteListing> findByIdForUpdate(@Param("id") Long id);

    /** Tin đang treo của một Note; chỉ có tối đa một tin, đảm bảo bằng index một phần. */
    Optional<NoteListing> findByNoteIdAndStatus(Long noteId, NoteListingStatus status);

    /** Bảng tin: tin đang mở, mới nhất trước. */
    Page<NoteListing> findByStatusOrderByCreatedAtDesc(NoteListingStatus status, Pageable pageable);

    /** Tin của một người bán, để họ theo dõi và rút tin. */
    Page<NoteListing> findBySellerIdOrderByCreatedAtDesc(String sellerId, Pageable pageable);
}
