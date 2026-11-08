package com.finora.loan.domain.servicing.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Lịch Fineract đã áp dụng sau một lần cơ cấu; bất biến để đối chiếu các phiên bản lịch. */
@Entity
@Table(name = "loan_servicing_schedule_versions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanServicingScheduleVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "finora_loan_id", nullable = false, updatable = false)
    private Long finoraLoanId;
    @Column(name = "reschedule_request_id", nullable = false, unique = true, updatable = false)
    private UUID rescheduleRequestId;
    @Column(name = "schedule_version", nullable = false, updatable = false)
    private int scheduleVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "periods_snapshot_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String periodsSnapshotJson;
    @Column(name = "data_as_of", nullable = false, updatable = false)
    private Instant dataAsOf;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static LoanServicingScheduleVersion create(Long loanId, UUID requestId, int version,
            String periodsJson, Instant now) {
        LoanServicingScheduleVersion value = new LoanServicingScheduleVersion();
        value.finoraLoanId = Objects.requireNonNull(loanId, "loanId");
        value.rescheduleRequestId = Objects.requireNonNull(requestId, "requestId");
        if (version <= 0) throw new IllegalArgumentException("version phải dương");
        value.scheduleVersion = version;
        if (periodsJson == null || periodsJson.isBlank()) throw new IllegalArgumentException("periodsJson trống");
        value.periodsSnapshotJson = periodsJson;
        value.dataAsOf = Objects.requireNonNull(now, "now");
        value.createdAt = now;
        return value;
    }
}
