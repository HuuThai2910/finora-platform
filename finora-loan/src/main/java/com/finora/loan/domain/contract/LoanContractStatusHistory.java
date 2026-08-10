package com.finora.loan.domain.contract;

import com.finora.loan.domain.application.ActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "loan_contract_status_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanContractStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private Long contractId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "from_status", length = 30, updatable = false)
    private LoanContractStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "to_status", nullable = false, length = 30, updatable = false)
    private LoanContractStatus toStatus;

    @Column(name = "reason_code", length = 50, updatable = false)
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actor_type", nullable = false, length = 20, updatable = false)
    private ActorType actorType;

    @Column(name = "actor_id", nullable = false, length = 100, updatable = false)
    private String actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "trace_id", length = 100, updatable = false)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** History append-only phải được insert trong cùng transaction với Contract transition. */
    public static LoanContractStatusHistory create(
            Long contractId,
            LoanContractStatus fromStatus,
            LoanContractStatus toStatus,
            String reasonCode,
            ActorType actorType,
            String actorId,
            Instant occurredAt,
            String traceId
    ) {
        LoanContractStatusHistory history = new LoanContractStatusHistory();
        history.contractId = contractId;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        history.reasonCode = normalize(reasonCode);
        history.actorType = actorType;
        history.actorId = actorId;
        history.occurredAt = occurredAt;
        history.traceId = normalize(traceId);
        history.createdAt = occurredAt;
        history.updatedAt = occurredAt;
        return history;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
