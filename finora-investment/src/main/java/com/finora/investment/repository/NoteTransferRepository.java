package com.finora.investment.repository;

import com.finora.investment.domain.secondary.NoteTransfer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteTransferRepository extends JpaRepository<NoteTransfer, Long> {

    /**
     * Một tin đăng bán sinh đúng một lần chuyển nhượng.
     *
     * <p>Dùng để nhận ra việc khớp đã chạy rồi: nếu mất kết nối sau khi chuyển tiền nhưng trước
     * khi trả kết quả, lần gọi lại đọc được bản ghi này và trả về cùng kết quả thay vì chuyển
     * tiền lần hai.</p>
     */
    Optional<NoteTransfer> findByNoteListingId(Long noteListingId);

    /** Lịch sử chuyển nhượng của một Note, mới nhất trước. */
    List<NoteTransfer> findByNoteIdOrderByTransferredAtDesc(Long noteId);
}
