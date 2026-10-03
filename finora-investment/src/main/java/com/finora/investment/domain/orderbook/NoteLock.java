package com.finora.investment.domain.orderbook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Note đang nằm trong một lệnh bán.
 *
 * <p>Khóa không chuyển quyền sở hữu: Note vẫn thuộc người bán và vẫn nhận gốc lãi cho tới khi
 * khớp. Unique theo {@code note_id} ở tầng dữ liệu bảo đảm một Note không bị bán qua hai lệnh.</p>
 */
@Entity
@Table(name = "order_book_note_locks")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoteLock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false, unique = true, updatable = false)
    private Long noteId;

    @Column(name = "ask_order_id", nullable = false, updatable = false)
    private Long askOrderId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
