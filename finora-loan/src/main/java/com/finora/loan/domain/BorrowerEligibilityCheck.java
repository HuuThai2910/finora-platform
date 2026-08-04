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

import java.time.Instant;

@Entity
@Table(name = "borrower_eligibility_checks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BorrowerEligibilityCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private Long applicationId;

    @Column(name = "borrower_id", nullable = false, length = 100, updatable = false)
    private String borrowerId;

    @Column(name = "request_id", nullable = false, length = 100, unique = true, updatable = false)
    private String requestId;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    private Integer age;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kyc_status", nullable = false, length = 30, updatable = false)
    private BorrowerKycStatus kycStatus;

    @Column(name = "kyc_reference", length = 100, updatable = false)
    private String kycReference;

    @Column(name = "kyc_version", length = 50, updatable = false)
    private String kycVersion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "income_verification_status", nullable = false, length = 30, updatable = false)
    private IncomeVerificationStatus incomeVerificationStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "profile_source", nullable = false, length = 30, updatable = false)
    private BorrowerProfileSource profileSource;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "eligibility_result", nullable = false, length = 30, updatable = false)
    private EligibilityResult eligibilityResult;

    @Column(name = "reason_code", length = 100, updatable = false)
    private String reasonCode;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "response_hash", nullable = false, length = 64, updatable = false)
    private String responseHash;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 100, updatable = false)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** Lưu đúng evidence tối thiểu đã dùng; tuyệt đối không sao chép ảnh giấy tờ hay CCCD từ User Service. */
    public static BorrowerEligibilityCheck capture(
            Long applicationId,
            String borrowerId,
            String requestId,
            Integer age,
            BorrowerKycStatus kycStatus,
            String kycReference,
            String kycVersion,
            IncomeVerificationStatus incomeVerificationStatus,
            BorrowerProfileSource profileSource,
            EligibilityResult result,
            String reasonCode,
            String requestHash,
            String responseHash,
            Instant now
    ) {
        BorrowerEligibilityCheck check = new BorrowerEligibilityCheck();
        check.applicationId = applicationId;
        check.borrowerId = borrowerId;
        check.requestId = requestId;
        check.age = age;
        check.kycStatus = kycStatus;
        check.kycReference = normalize(kycReference);
        check.kycVersion = normalize(kycVersion);
        check.incomeVerificationStatus = incomeVerificationStatus;
        check.profileSource = profileSource;
        check.eligibilityResult = result;
        check.reasonCode = normalize(reasonCode);
        check.requestHash = requestHash;
        check.responseHash = responseHash;
        check.checkedAt = now;
        check.createdBy = "SYSTEM";
        check.updatedBy = "SYSTEM";
        check.createdAt = now;
        check.updatedAt = now;
        return check;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
