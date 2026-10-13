package com.finora.investment.repository;

import com.finora.investment.domain.secondary.NoteRepaymentRedirect;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoteRepaymentRedirectRepository extends JpaRepository<NoteRepaymentRedirect, Long> {

    boolean existsByRepaymentIdAndNoteId(UUID repaymentId, Long noteId);

    /** Khoản chuyển lại đã tới lượt thử; đi theo index một phần {@code idx_note_repayment_redirects_due}. */
    @Query("""
            SELECT r FROM NoteRepaymentRedirect r
            WHERE r.status = 'PENDING' AND r.nextAttemptAt <= :now
            ORDER BY r.nextAttemptAt ASC, r.id ASC
            """)
    List<NoteRepaymentRedirect> findDue(@Param("now") Instant now, Pageable pageable);
}
