package com.finora.loan.domain.collection;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "loan_collection_actions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanCollectionAction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "action_id", nullable = false, unique = true, updatable = false) private UUID actionId;
    @Column(name = "collection_case_id", nullable = false, updatable = false) private Long collectionCaseId;
    @Enumerated(EnumType.STRING) @Column(name = "action_type", nullable = false, length = 40, updatable = false)
    private CollectionActionType actionType;
    @Column(length = 500, updatable = false) private String note;
    @Column(name = "promise_date", updatable = false) private LocalDate promiseDate;
    @Column(name = "promise_amount", precision = 18, scale = 2, updatable = false) private BigDecimal promiseAmount;
    @Column(name = "actor_id", nullable = false, length = 100, updatable = false) private String actorId;
    @Column(name = "idempotency_key", nullable = false, length = 150, updatable = false) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false) private String requestHash;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    public static LoanCollectionAction record(Long caseId, CollectionActionType type, String note,
            LocalDate promiseDate, BigDecimal promiseAmount, String actorId, String key, String hash, Instant now) {
        if (type == CollectionActionType.PROMISE_TO_PAY
                && (promiseDate == null || promiseAmount == null || promiseAmount.signum() <= 0)) {
            throw new IllegalArgumentException("Cam kết trả nợ cần ngày và số tiền dương");
        }
        if (type != CollectionActionType.PROMISE_TO_PAY && (promiseDate != null || promiseAmount != null)) {
            throw new IllegalArgumentException("Chỉ PROMISE_TO_PAY nhận thông tin cam kết");
        }
        LoanCollectionAction value = new LoanCollectionAction();
        value.actionId = UUID.randomUUID();
        value.collectionCaseId = Objects.requireNonNull(caseId);
        value.actionType = Objects.requireNonNull(type);
        value.note = optional(note, 500);
        value.promiseDate = promiseDate;
        value.promiseAmount = promiseAmount == null ? null : promiseAmount.setScale(2, RoundingMode.HALF_UP);
        value.actorId = required(actorId, 100);
        value.idempotencyKey = required(key, 150);
        value.requestHash = required(hash, 64);
        value.createdAt = Objects.requireNonNull(now);
        return value;
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Giá trị bắt buộc bị trống");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("Giá trị quá dài");
        return normalized;
    }
    private static String optional(String value, int max) {
        return value == null || value.isBlank() ? null : required(value, max);
    }
}
