package com.finora.notification.repository;

import com.finora.notification.domain.DeferredEvent;
import com.finora.notification.domain.DeferredEventStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeferredEventRepository extends JpaRepository<DeferredEvent, Long> {

    Optional<DeferredEvent> findByEventId(UUID eventId);

    /** Worker: dùng index {@code (status, next_attempt_at, id)}, lô hữu hạn. */
    @Query("""
            select d.id from DeferredEvent d
            where d.status = :status and d.nextAttemptAt <= :now
            order by d.nextAttemptAt, d.id
            """)
    List<Long> findDueIds(@Param("status") DeferredEventStatus status, @Param("now") Instant now, Pageable pageable);

    /** Khóa đúng một dòng còn chờ; worker khác đang giữ thì bỏ qua thay vì đợi (SKIP LOCKED). */
    @Query(value = """
            SELECT * FROM notification_deferred_events
            WHERE id = :id AND status = 'WAITING'
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<DeferredEvent> lockWaiting(@Param("id") Long id);

    /** Màn vận hành; số dòng nhỏ (chỉ event lệch thứ tự) nên không thêm index riêng cho sắp xếp. */
    Page<DeferredEvent> findByStatusOrderByIdDesc(DeferredEventStatus status, Pageable pageable);
}
