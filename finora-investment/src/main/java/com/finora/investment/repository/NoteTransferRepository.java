package com.finora.investment.repository;

import com.finora.investment.domain.secondary.NoteTransfer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteTransferRepository extends JpaRepository<NoteTransfer, Long> {

    /** Lịch sử chuyển nhượng của một Note, mới nhất trước. */
    List<NoteTransfer> findByNoteIdOrderByTransferredAtDesc(Long noteId);

    List<NoteTransfer> findByTradeIdOrderByNoteIdAsc(Long tradeId);
}
