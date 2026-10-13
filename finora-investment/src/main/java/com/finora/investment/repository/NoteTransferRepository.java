package com.finora.investment.repository;

import com.finora.investment.domain.secondary.NoteTransfer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteTransferRepository extends JpaRepository<NoteTransfer, Long> {

    /** Lịch sử chuyển nhượng của một Note, mới nhất trước. */
    List<NoteTransfer> findByNoteIdOrderByTransferredAtDesc(Long noteId);

    List<NoteTransfer> findByTradeIdOrderByNoteIdAsc(Long tradeId);

    /** Người này từng bán Note này — điều kiện để coi một lần trả nợ cho họ là trả nhầm chủ cũ. */
    boolean existsByNoteIdAndSellerId(Long noteId, String sellerId);
}
