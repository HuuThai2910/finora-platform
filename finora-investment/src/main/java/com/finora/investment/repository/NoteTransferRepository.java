package com.finora.investment.repository;

import com.finora.investment.domain.secondary.entity.NoteTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface NoteTransferRepository extends JpaRepository<NoteTransfer, Long> {

    /** Lịch sử chuyển nhượng của một Note, mới nhất trước. */
    List<NoteTransfer> findByNoteIdOrderByTransferredAtDesc(Long noteId);

    List<NoteTransfer> findByTradeIdOrderByNoteIdAsc(Long tradeId);

    /** Số lần đổi chủ của từng Note trong danh sách, một truy vấn cho cả trang. */
    @Query("""
            SELECT new com.finora.investment.repository.NoteTransferCountView(t.noteId, COUNT(t))
            FROM NoteTransfer t
            WHERE t.noteId IN :noteIds
            GROUP BY t.noteId
            """)
    List<NoteTransferCountView> countByNoteIds(@Param("noteIds") Collection<Long> noteIds);

    /** Số Note của một đợt đã đổi chủ ít nhất một lần. */
    @Query("""
            SELECT COUNT(DISTINCT t.noteId) FROM NoteTransfer t
            WHERE t.noteId IN (SELECT n.id FROM InvestmentNote n WHERE n.listingId = :listingId)
            """)
    long countTransferredNotesByListing(@Param("listingId") Long listingId);

    /** Người này từng bán Note này — điều kiện để coi một lần trả nợ cho họ là trả nhầm chủ cũ. */
    boolean existsByNoteIdAndSellerId(Long noteId, String sellerId);
}
