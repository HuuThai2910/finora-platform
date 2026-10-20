package com.finora.loan.domain.application;

import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.domain.scoring.IncomeVerificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Trạng thái eKYC và tuổi của người vay chụp đúng lúc nộp hồ sơ (W2-KYC). Worker kiểm điều kiện và AI dùng bản này vì
 * chạy nền, không có token người vay để hỏi lại User; người vay đổi eKYC sau đó không làm đổi hồ sơ đã nộp.
 *
 * <p>Hồ sơ nộp trước Loan V29 không có snapshot: mọi cột đều null và Hibernate trả {@code null} cho cả object.</p>
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BorrowerKycSnapshot {

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kyc_snapshot_status", length = 30, updatable = false)
    private BorrowerKycStatus kycStatus;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "kyc_snapshot_age", updatable = false)
    private Integer age;

    @Column(name = "kyc_snapshot_reference", length = 100, updatable = false)
    private String kycReference;

    @Column(name = "kyc_snapshot_version", length = 50, updatable = false)
    private String kycVersion;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kyc_snapshot_income_status", length = 30, updatable = false)
    private IncomeVerificationStatus incomeVerificationStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kyc_snapshot_source", length = 30, updatable = false)
    private BorrowerProfileSource source;

    @Column(name = "kyc_snapshot_captured_at", updatable = false)
    private Instant capturedAt;

    public static BorrowerKycSnapshot capture(
            BorrowerKycStatus kycStatus,
            Integer age,
            String kycReference,
            String kycVersion,
            IncomeVerificationStatus incomeVerificationStatus,
            BorrowerProfileSource source,
            Instant capturedAt
    ) {
        BorrowerKycSnapshot snapshot = new BorrowerKycSnapshot();
        snapshot.kycStatus = Objects.requireNonNull(kycStatus, "kycStatus");
        snapshot.age = age;
        snapshot.kycReference = kycReference;
        snapshot.kycVersion = kycVersion;
        snapshot.incomeVerificationStatus = Objects.requireNonNull(incomeVerificationStatus, "incomeVerificationStatus");
        snapshot.source = Objects.requireNonNull(source, "source");
        snapshot.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        return snapshot;
    }
}
