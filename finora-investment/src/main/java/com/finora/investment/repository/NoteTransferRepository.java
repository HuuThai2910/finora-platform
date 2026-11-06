package com.finora.investment.repository;

import com.finora.investment.domain.secondary.entity.NoteTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoteTransferRepository extends JpaRepository<NoteTransfer, Long> {

    /** Lịch sử chuyển nhượng của một Note, mới nhất trước. */
    List<NoteTransfer> findByNoteIdOrderByTransferredAtDesc(Long noteId);

    List<NoteTransfer> findByTradeIdOrderByNoteIdAsc(Long tradeId);

    /** Người này từng bán Note này — điều kiện để coi một lần trả nợ cho họ là trả nhầm chủ cũ. */
    boolean existsByNoteIdAndSellerId(Long noteId, String sellerId);
}
