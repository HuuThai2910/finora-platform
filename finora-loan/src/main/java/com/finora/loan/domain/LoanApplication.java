package com.finora.loan.domain;

import com.finora.loan.exception.LoanBusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "loan_applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_number", nullable = false, length = 30, unique = true, updatable = false)
    private String applicationNumber;

    @Column(name = "borrower_id", nullable = false, length = 100, updatable = false)
    private String borrowerId;

    @Column(name = "idempotency_key", nullable = false, length = 150, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "loan_product_id", nullable = false, updatable = false)
    private Long loanProductId;

    @Column(name = "requested_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal requestedAmount;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "requested_term_months", nullable = false, updatable = false)
    private Integer requestedTermMonths;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "purpose_code", nullable = false, length = 30, updatable = false)
    private LoanPurpose purposeCode;

    @Column(name = "purpose_detail", length = 500, updatable = false)
    private String purposeDetail;

    @Embedded
    private ApplicantFinancialSnapshot financialSnapshot;

    @Column(name = "product_code_snapshot", nullable = false, length = 50, updatable = false)
    private String productCodeSnapshot;

    @Column(name = "product_name_snapshot", nullable = false, length = 150, updatable = false)
    private String productNameSnapshot;

    @Column(name = "product_configuration_version_snapshot", nullable = false, updatable = false)
    private Long productConfigurationVersionSnapshot;

    @Column(name = "product_min_amount_snapshot", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal productMinAmountSnapshot;

    @Column(name = "product_max_amount_snapshot", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal productMaxAmountSnapshot;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "product_min_term_months_snapshot", nullable = false, updatable = false)
    private Integer productMinTermMonthsSnapshot;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "product_max_term_months_snapshot", nullable = false, updatable = false)
    private Integer productMaxTermMonthsSnapshot;

    @Column(name = "annual_interest_rate_snapshot", nullable = false, precision = 7, scale = 4, updatable = false)
    private BigDecimal annualInterestRateSnapshot;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "repayment_method_snapshot", nullable = false, length = 30, updatable = false)
    private RepaymentMethod repaymentMethodSnapshot;

    @Column(name = "fineract_product_id_snapshot", nullable = false, updatable = false)
    private Long fineractProductIdSnapshot;

    @Column(name = "core_mapping_id_snapshot", nullable = false, updatable = false)
    private Long coreMappingIdSnapshot;

    @Column(name = "core_config_version_snapshot", nullable = false, length = 30, updatable = false)
    private String coreConfigVersionSnapshot;

    @Column(name = "expected_disbursement_date", nullable = false, updatable = false)
    private LocalDate expectedDisbursementDate;

    @Column(name = "submission_calculation_snapshot_id")
    private Long submissionCalculationSnapshotId;

    @Column(name = "latest_credit_assessment_id")
    private Long latestCreditAssessmentId;

    @Column(name = "pricing_disclosure_version_snapshot", nullable = false, length = 50, updatable = false)
    private String pricingDisclosureVersionSnapshot;

    @Column(name = "pricing_disclosure_accepted_at", nullable = false, updatable = false)
    private Instant pricingDisclosureAcceptedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private LoanApplicationStatus status;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdrawal_reason", length = 500)
    private String withdrawalReason;

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

    /** Tạo thẳng SUBMITTED; form chưa nộp không tồn tại trong database Loan. */
    public static LoanApplication submit(
            String applicationNumber,
            String borrowerId,
            String idempotencyKey,
            String requestHash,
            LoanProduct product,
            FineractProductMapping mapping,
            BigDecimal requestedAmount,
            Integer requestedTermMonths,
            LoanPurpose purposeCode,
            String purposeDetail,
            ApplicantFinancialSnapshot financialSnapshot,
            LocalDate expectedDisbursementDate,
            String pricingDisclosureVersion,
            Instant now
    ) {
        validatePurpose(purposeCode, purposeDetail);
        product.requireAvailable();
        product.requireRequestedTerms(requestedAmount, requestedTermMonths);
        if (mapping.getFineractProductId() == null) {
            throw LoanBusinessException.conflict("LOAN_PRODUCT_NOT_AVAILABLE", "Mapping Fineract chưa hoàn tất");
        }
        LoanApplication application = new LoanApplication();
        application.applicationNumber = requireText(applicationNumber, "applicationNumber");
        application.borrowerId = requireText(borrowerId, "borrowerId");
        application.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        application.requestHash = requireText(requestHash, "requestHash");
        application.loanProductId = product.getId();
        application.requestedAmount = requestedAmount.setScale(2, RoundingMode.HALF_UP);
        application.requestedTermMonths = requestedTermMonths;
        application.purposeCode = purposeCode;
        application.purposeDetail = normalizeOptional(purposeDetail);
        application.financialSnapshot = financialSnapshot;
        application.productCodeSnapshot = product.getCode();
        application.productNameSnapshot = product.getName();
        application.productConfigurationVersionSnapshot = product.getConfigurationVersion();
        application.productMinAmountSnapshot = product.getMinAmount();
        application.productMaxAmountSnapshot = product.getMaxAmount();
        application.productMinTermMonthsSnapshot = product.getMinTermMonths();
        application.productMaxTermMonthsSnapshot = product.getMaxTermMonths();
        application.annualInterestRateSnapshot = product.getAnnualInterestRate();
        application.repaymentMethodSnapshot = product.getRepaymentMethod();
        application.fineractProductIdSnapshot = mapping.getFineractProductId();
        application.coreMappingIdSnapshot = mapping.getId();
        application.coreConfigVersionSnapshot = mapping.getConfigVersion();
        application.expectedDisbursementDate = expectedDisbursementDate;
        application.pricingDisclosureVersionSnapshot = requireText(pricingDisclosureVersion, "pricingDisclosureVersion");
        application.pricingDisclosureAcceptedAt = now;
        application.status = LoanApplicationStatus.SUBMITTED;
        application.submittedAt = now;
        application.createdBy = borrowerId;
        application.updatedBy = borrowerId;
        application.createdAt = now;
        application.updatedAt = now;
        return application;
    }

    /** Snapshot được insert sau Application để lấy identity ID nhưng vẫn trong cùng local transaction. */
    public void attachSubmissionCalculationSnapshot(Long snapshotId) {
        if (submissionCalculationSnapshotId != null || snapshotId == null) {
            throw new IllegalStateException("Calculation snapshot của hồ sơ không hợp lệ");
        }
        submissionCalculationSnapshotId = snapshotId;
    }

    /** Bắt đầu kiểm tra điều kiện nhưng chưa coi mock/User response là hợp lệ trước khi provider trả về. */
    public void startEligibility(String actorId, Instant now) {
        requireStatus(LoanApplicationStatus.SUBMITTED);
        transitionTo(LoanApplicationStatus.ELIGIBILITY_PENDING, actorId, now);
    }

    /** Chỉ hồ sơ đã qua eligibility mới được liên kết với assessment và gửi sang AI. */
    public void startScoring(Long assessmentId, String actorId, Instant now) {
        requireStatus(LoanApplicationStatus.ELIGIBILITY_PENDING);
        if (assessmentId == null) {
            throw new IllegalArgumentException("assessmentId không được để trống");
        }
        latestCreditAssessmentId = assessmentId;
        transitionTo(LoanApplicationStatus.SCORING, actorId, now);
    }

    public void markScoringRetryPending(String actorId, Instant now) {
        requireStatus(LoanApplicationStatus.SCORING);
        transitionTo(LoanApplicationStatus.SCORING_RETRY_PENDING, actorId, now);
    }

    /** MVP luôn đưa kết quả AI cho admin review; AI không có quyền tự phê duyệt hồ sơ. */
    public void markPendingReview(String actorId, Instant now) {
        if (status != LoanApplicationStatus.SCORING && status != LoanApplicationStatus.SCORING_RETRY_PENDING) {
            throw invalidStatus(LoanApplicationStatus.PENDING_REVIEW);
        }
        transitionTo(LoanApplicationStatus.PENDING_REVIEW, actorId, now);
    }

    public void rejectAfterEligibility(String actorId, Instant now) {
        requireStatus(LoanApplicationStatus.ELIGIBILITY_PENDING);
        transitionTo(LoanApplicationStatus.REJECTED, actorId, now);
    }

    public void markEligibilityManualReview(String actorId, Instant now) {
        requireStatus(LoanApplicationStatus.ELIGIBILITY_PENDING);
        transitionTo(LoanApplicationStatus.PENDING_REVIEW, actorId, now);
    }

    public void resumeScoring(String actorId, Instant now) {
        requireStatus(LoanApplicationStatus.SCORING_RETRY_PENDING);
        transitionTo(LoanApplicationStatus.SCORING, actorId, now);
    }

    public void reopenScoring(long expectedVersion, Long assessmentId, String actorId, Instant now) {
        requireVersion(expectedVersion);
        requireStatus(LoanApplicationStatus.PENDING_REVIEW);
        if (assessmentId == null || !assessmentId.equals(latestCreditAssessmentId)) {
            throw LoanBusinessException.conflict(
                    "CREDIT_ASSESSMENT_NOT_LATEST",
                    "Chỉ assessment mới nhất của hồ sơ mới được retry"
            );
        }
        transitionTo(LoanApplicationStatus.SCORING_RETRY_PENDING, actorId, now);
    }

    public void withdraw(long expectedVersion, String reason, String actorId, Instant now) {
        requireOwner(actorId);
        requireVersion(expectedVersion);
        if (status != LoanApplicationStatus.SUBMITTED) {
            throw LoanBusinessException.conflict(
                    "INVALID_APPLICATION_TRANSITION",
                    "Chỉ hồ sơ đang chờ xử lý mới được rút"
            );
        }
        status = LoanApplicationStatus.WITHDRAWN;
        withdrawnAt = now;
        withdrawalReason = normalizeOptional(reason);
        updatedBy = actorId;
        updatedAt = now;
    }

    public void requireOwner(String actorId) {
        if (!borrowerId.equals(actorId)) {
            throw LoanBusinessException.forbidden(
                    "LOAN_APPLICATION_ACCESS_DENIED",
                    "Bạn không có quyền thao tác hồ sơ vay này"
            );
        }
    }

    private void requireVersion(long expectedVersion) {
        long currentVersion = version == null ? 0L : version;
        if (currentVersion != expectedVersion) {
            throw LoanBusinessException.conflict(
                    "LOAN_APPLICATION_VERSION_CONFLICT",
                    "Hồ sơ đã được cập nhật bởi yêu cầu khác"
            );
        }
    }

    private void requireStatus(LoanApplicationStatus expected) {
        if (status != expected) {
            throw invalidStatus(expected);
        }
    }

    private LoanBusinessException invalidStatus(LoanApplicationStatus target) {
        return LoanBusinessException.conflict(
                "INVALID_APPLICATION_TRANSITION",
                "Không thể chuyển hồ sơ từ " + status + " sang " + target
        );
    }

    private void transitionTo(LoanApplicationStatus target, String actorId, Instant now) {
        status = target;
        updatedBy = requireText(actorId, "actorId");
        updatedAt = now;
    }

    private static void validatePurpose(LoanPurpose purposeCode, String purposeDetail) {
        if (purposeCode == null) {
            throw LoanBusinessException.badRequest("LOAN_PURPOSE_REQUIRED", "Phải chọn mục đích vay");
        }
        String normalized = normalizeOptional(purposeDetail);
        if (normalized != null && normalized.length() > 500) {
            throw LoanBusinessException.badRequest("PURPOSE_DETAIL_TOO_LONG", "Chi tiết mục đích tối đa 500 ký tự");
        }
        if (purposeCode.isRequiresDetail() && normalized == null) {
            throw LoanBusinessException.badRequest(
                    "LOAN_PURPOSE_DETAIL_REQUIRED",
                    "Phải mô tả chi tiết khi chọn mục đích khác"
            );
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
