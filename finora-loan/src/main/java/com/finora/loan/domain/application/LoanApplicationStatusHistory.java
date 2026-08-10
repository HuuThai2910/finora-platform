package com.finora.loan.domain.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
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
@Table(name = "loan_application_status_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanApplicationStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_application_id", nullable = false, updatable = false)
    private Long loanApplicationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "from_status", length = 30, updatable = false)
    private LoanApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "to_status", nullable = false, length = 30, updatable = false)
    private LoanApplicationStatus toStatus;

    @Column(name = "reason_code", length = 50, updatable = false)
    private String reasonCode;

    @Column(name = "reason_detail", length = 1000, updatable = false)
    private String reasonDetail;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "actor_type", nullable = false, length = 20, updatable = false)
    private ActorType actorType;

    @Column(name = "actor_id", length = 100, updatable = false)
    private String actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** Tạo audit row bất biến trong cùng transaction với state transition của LoanApplication. */
    public static LoanApplicationStatusHistory create(
            Long applicationId,
            LoanApplicationStatus fromStatus,
            LoanApplicationStatus toStatus,
            String reasonCode,
            String reasonDetail,
            ActorType actorType,
            String actorId,
            Instant now
    ) {
        LoanApplicationStatusHistory history = new LoanApplicationStatusHistory();
        history.loanApplicationId = applicationId;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        history.reasonCode = reasonCode;
        history.reasonDetail = reasonDetail == null || reasonDetail.isBlank() ? null : reasonDetail.trim();
        history.actorType = actorType;
        history.actorId = actorId;
        history.createdAt = now;
        history.updatedAt = now;
        return history;
    }
}
