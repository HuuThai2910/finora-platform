package com.finora.investment.repository;

import com.finora.investment.domain.orderbook.NoteLock;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
