package com.finora.notification.repository;

import com.finora.notification.domain.PushDelivery;
import com.finora.notification.domain.PushDeliveryStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushDeliveryRepository extends JpaRepository<PushDelivery, Long> {

    /**
     * Lô đến lượt gửi, gồm cả SENDING đã quá lease (worker trước chết). SKIP LOCKED: hai instance chạy cùng lúc nhận hai
     * lô rời nhau thay vì gửi trùng hay chờ nhau. Dùng index {@code (status, next_attempt_at, id)}.
     */
    @Query(value = """
            SELECT id FROM notification_push_deliveries
            WHERE status IN ('PENDING', 'SENDING') AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockDueForDispatch(@Param("now") Instant now, @Param("limit") int limit);

    /** Ticket Expo đến lúc đọc receipt; index {@code (status, receipt_check_after, id)}. */
    @Query(value = """
            SELECT id FROM notification_push_deliveries
            WHERE status = 'SENT' AND receipt_check_after <= :now
            ORDER BY receipt_check_after, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockDueForReceipt(@Param("now") Instant now, @Param("limit") int limit);

    /** Ghi kết quả: khóa lại đúng các dòng đã claim để so claim token với lần ghi đồng thời khác. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PushDelivery d where d.id in :ids")
    List<PushDelivery> findAllForUpdate(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from PushDelivery d where d.id = :id")
    java.util.Optional<PushDelivery> findByIdForUpdate(@Param("id") Long id);

    /** Màn vận hành; index {@code (status, updated_at DESC, id DESC)}. */
    Page<PushDelivery> findByStatusOrderByUpdatedAtDescIdDesc(PushDeliveryStatus status, Pageable pageable);

    List<PushDelivery> findByNotificationId(UUID notificationId);
}
