package com.finora.loan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "borrower_credit_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BorrowerCreditProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "borrower_id", nullable = false, length = 100, unique = true, updatable = false)
    private String borrowerId;

    @Column(name = "has_internal_credit_history", nullable = false)
    private boolean hasInternalCreditHistory;

    @Column(name = "internal_delinquencies_last_2_years", nullable = false)
    private int internalDelinquenciesLast2Years;

    @Column(name = "internal_defaulted_loan_count", nullable = false)
    private int internalDefaultedLoanCount;

    @Column(name = "completed_loan_count", nullable = false)
    private int completedLoanCount;

    @Column(name = "on_time_payment_rate", precision = 7, scale = 4)
    private BigDecimal onTimePaymentRate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private CreditProfileSource source;

    @Column(name = "calculation_policy_version", nullable = false, length = 50)
    private String calculationPolicyVersion;

    @Column(name = "source_event_id", length = 100)
    private String sourceEventId;

    @Column(name = "source_event_at")
    private Instant sourceEventAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Người vay mới có một projection rõ nguồn NO_HISTORY; hai số 0 không được mô tả thành dữ liệu CIC. */
    public static BorrowerCreditProfile noHistory(String borrowerId, Instant now) {
        BorrowerCreditProfile profile = new BorrowerCreditProfile();
        profile.borrowerId = borrowerId;
        profile.hasInternalCreditHistory = false;
        profile.internalDelinquenciesLast2Years = 0;
        profile.internalDefaultedLoanCount = 0;
        profile.completedLoanCount = 0;
        profile.source = CreditProfileSource.NO_HISTORY;
        profile.calculationPolicyVersion = "FINORA_INTERNAL_CREDIT_V1";
        profile.createdBy = "SYSTEM";
        profile.updatedBy = "SYSTEM";
        profile.createdAt = now;
        profile.updatedAt = now;
        return profile;
    }
}
