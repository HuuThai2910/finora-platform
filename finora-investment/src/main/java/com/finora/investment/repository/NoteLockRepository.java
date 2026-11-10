package com.finora.investment.repository;

import com.finora.investment.domain.orderbook.entity.NoteLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface NoteLockRepository extends JpaRepository<NoteLock, Long> {

    /** Mở khóa các Note còn lại của một lệnh bán bị huỷ. */
    @Modifying
    @Query("DELETE FROM NoteLock l WHERE l.askOrderId = :askOrderId")
    int deleteByAskOrderId(@Param("askOrderId") Long askOrderId);

    /** Mở khóa các Note vừa được bán. */
    @Modifying
    @Query("DELETE FROM NoteLock l WHERE l.noteId IN :noteIds")
    int deleteByNoteIdIn(@Param("noteIds") Collection<Long> noteIds);

    long countByAskOrderId(Long askOrderId);

    /** Giá của lệnh bán đang khóa từng Note trong danh sách; Note không nằm trong lệnh bán nào thì không có dòng. */
    @Query("""
            SELECT new com.finora.investment.repository.NoteAskPriceView(l.noteId, o.pricePermille)
            FROM NoteLock l, BookOrder o
            WHERE o.id = l.askOrderId AND l.noteId IN :noteIds
            """)
    java.util.List<NoteAskPriceView> findAskPrices(@Param("noteIds") Collection<Long> noteIds);
}
