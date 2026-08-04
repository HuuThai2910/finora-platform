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
@Table(name = "credit_scoring_assessments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditScoringAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private Long applicationId;

    @Column(name = "eligibility_check_id", nullable = false, updatable = false)
    private Long eligibilityCheckId;

    @Column(name = "request_id", nullable = false, length = 100, unique = true, updatable = false)
    private String requestId;

    @Column(name = "logical_scoring_key", nullable = false, length = 150, unique = true, updatable = false)
    private String logicalScoringKey;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private CreditAssessmentStatus status;

    @Column(name = "requested_model_version", nullable = false, length = 30, updatable = false)
    private String requestedModelVersion;

    @Column(name = "actual_model_version", length = 30)
    private String actualModelVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String inputSnapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_source_snapshot_json", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String inputSourceSnapshotJson;

    @Column(name = "input_hash", nullable = false, length = 64, updatable = false)
    private String inputHash;

    @Column(name = "pd_probability", precision = 10, scale = 8)
    private BigDecimal pdProbability;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "evaluation_score", precision = 9, scale = 4)
    private BigDecimal evaluationScore;

    @Column(name = "credit_grade", length = 5)
    private String creditGrade;

    @Column(name = "suggested_limit", precision = 18, scale = 2)
    private BigDecimal suggestedLimit;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "ai_recommendation", length = 30)
    private AiRecommendation aiRecommendation;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_snapshot_json", columnDefinition = "jsonb")
    private String responseSnapshotJson;

    @Column(name = "response_hash", length = 64)
    private String responseHash;

    @Column(name = "decision_policy_version", length = 50)
    private String decisionPolicyVersion;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_detail", length = 1000)
    private String failureDetail;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "scored_at")
    private Instant scoredAt;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

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

    /** Input được đóng băng trước khi ra mạng; retry kỹ thuật luôn dùng lại chính JSON và inputHash này. */
    public static CreditScoringAssessment pending(
            Long applicationId,
            Long eligibilityCheckId,
            String requestId,
            String logicalScoringKey,
            String requestedModelVersion,
            String inputSnapshotJson,
            String inputSourceSnapshotJson,
            String inputHash,
            Instant now
    ) {
        CreditScoringAssessment assessment = new CreditScoringAssessment();
        assessment.applicationId = applicationId;
        assessment.eligibilityCheckId = eligibilityCheckId;
        assessment.requestId = requestId;
        assessment.logicalScoringKey = logicalScoringKey;
        assessment.status = CreditAssessmentStatus.PENDING;
        assessment.requestedModelVersion = requestedModelVersion;
        assessment.inputSnapshotJson = inputSnapshotJson;
        assessment.inputSourceSnapshotJson = inputSourceSnapshotJson;
        assessment.inputHash = inputHash;
        assessment.createdBy = "SYSTEM";
        assessment.updatedBy = "SYSTEM";
        assessment.createdAt = now;
        assessment.updatedAt = now;
        return assessment;
    }

    public void markProcessing(Instant now) {
        if (status != CreditAssessmentStatus.PENDING
                && status != CreditAssessmentStatus.RETRY_PENDING
                && status != CreditAssessmentStatus.PROCESSING) {
            throw new IllegalStateException("Assessment không ở trạng thái có thể thực thi");
        }
        status = CreditAssessmentStatus.PROCESSING;
        attemptCount++;
        startedAt = now;
        nextRetryAt = null;
        failureCode = null;
        failureDetail = null;
        updatedAt = now;
    }

    /** Kết quả SUCCEEDED bất biến; suggested_rate không có tham số nên không thể lọt vào domain Loan. */
    public void markSucceeded(
            String actualModelVersion,
            BigDecimal pdProbability,
            Integer riskScore,
            BigDecimal evaluationScore,
            String creditGrade,
            BigDecimal suggestedLimit,
            AiRecommendation recommendation,
            String rejectionReason,
            String responseSnapshotJson,
            String responseHash,
            String decisionPolicyVersion,
            Instant now
    ) {
        requireProcessing();
        this.status = CreditAssessmentStatus.SUCCEEDED;
        this.actualModelVersion = actualModelVersion;
        this.pdProbability = pdProbability;
        this.riskScore = riskScore;
        this.evaluationScore = evaluationScore;
        this.creditGrade = creditGrade;
        this.suggestedLimit = suggestedLimit;
        this.aiRecommendation = recommendation;
        this.rejectionReason = normalize(rejectionReason);
        this.responseSnapshotJson = responseSnapshotJson;
        this.responseHash = responseHash;
        this.decisionPolicyVersion = decisionPolicyVersion;
        this.completedAt = now;
        this.scoredAt = now;
        this.updatedAt = now;
    }

    public void markRetryPending(String code, String detail, Instant retryAt, Instant now) {
        requireProcessing();
        status = CreditAssessmentStatus.RETRY_PENDING;
        failureCode = normalize(code);
        failureDetail = normalize(detail);
        nextRetryAt = retryAt;
        updatedAt = now;
    }

    public void markFailed(String code, String detail, Instant now) {
        requireProcessing();
        status = CreditAssessmentStatus.FAILED;
        failureCode = normalize(code);
        failureDetail = normalize(detail);
        completedAt = now;
        nextRetryAt = null;
        updatedAt = now;
    }

    public void reopen(Instant now) {
        if (status != CreditAssessmentStatus.FAILED) {
            throw new IllegalStateException("Chỉ assessment FAILED mới được mở retry thủ công");
        }
        status = CreditAssessmentStatus.RETRY_PENDING;
        completedAt = null;
        nextRetryAt = now;
        updatedAt = now;
    }

    private void requireProcessing() {
        if (status != CreditAssessmentStatus.PROCESSING) {
            throw new IllegalStateException("Assessment không ở trạng thái PROCESSING");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
