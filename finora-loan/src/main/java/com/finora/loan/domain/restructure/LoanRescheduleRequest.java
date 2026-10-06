package com.finora.loan.domain.restructure;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Durable state machine cho một yêu cầu cơ cấu; không gọi Fineract từ entity này. */
@Entity
@Table(name = "loan_reschedule_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanRescheduleRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "request_id", nullable = false, unique = true, updatable = false)
    private UUID requestId;
    @Column(name = "finora_loan_id", nullable = false, updatable = false)
    private Long finoraLoanId;
    @Column(name = "loan_number", nullable = false, length = 50, updatable = false)
    private String loanNumber;
    @Column(name = "borrower_id", nullable = false, length = 100, updatable = false)
    private String borrowerId;
    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 40, updatable = false)
    private LoanRescheduleType requestType;
    @Column(name = "reschedule_from_date", nullable = false, updatable = false)
    private LocalDate rescheduleFromDate;
    @Column(name = "adjusted_due_date", updatable = false)
    private LocalDate adjustedDueDate;
    @Column(name = "extra_terms", updatable = false)
    private Integer extraTerms;
    @Column(name = "reason_comment", nullable = false, length = 500, updatable = false)
    private String reasonComment;
    @Column(name = "terms_version", nullable = false, length = 50, updatable = false)
    private String termsVersion;
    @Column(name = "terms_hash", nullable = false, length = 64, updatable = false)
    private String termsHash;
    @Column(name = "idempotency_key", nullable = false, length = 150, updatable = false)
    private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private LoanRescheduleStatus status;
    @Column(name = "fineract_reschedule_id")
    private Long fineractRescheduleId;
    @Column(name = "admin_id", length = 100)
    private String adminId;
    @Column(name = "decision_comment", length = 500)
    private String decisionComment;
    @Column(name = "decision_idempotency_key", length = 150)
    private String decisionIdempotencyKey;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "original_maturity_date")
    private LocalDate originalMaturityDate;
    @Column(name = "new_maturity_date")
    private LocalDate newMaturityDate;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "processing_started_at")
    private Instant processingStartedAt;
    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;
    @Version @Column(nullable = false)
    private Long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static LoanRescheduleRequest submit(UUID requestId, Long finoraLoanId, String loanNumber,
            String borrowerId, LoanRescheduleType type, LocalDate rescheduleFromDate,
            LocalDate adjustedDueDate, Integer extraTerms, String reasonComment, String termsVersion,
            String termsHash, String idempotencyKey, String requestHash, LocalDate maturityDate, Instant now) {
        LoanRescheduleRequest value = new LoanRescheduleRequest();
        value.requestId = Objects.requireNonNull(requestId, "requestId");
        value.finoraLoanId = Objects.requireNonNull(finoraLoanId, "finoraLoanId");
        value.loanNumber = requireText(loanNumber, "loanNumber", 50);
        value.borrowerId = requireText(borrowerId, "borrowerId", 100);
        value.requestType = Objects.requireNonNull(type, "type");
        value.rescheduleFromDate = Objects.requireNonNull(rescheduleFromDate, "rescheduleFromDate");
        value.reasonComment = requireText(reasonComment, "reasonComment", 500);
        value.termsVersion = requireText(termsVersion, "termsVersion", 50);
        value.termsHash = requireHash(termsHash, "termsHash");
        value.idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 150);
        value.requestHash = requireHash(requestHash, "requestHash");
        value.originalMaturityDate = maturityDate;
        if (type == LoanRescheduleType.INSTALLMENT_ADJUSTMENT) {
            value.adjustedDueDate = Objects.requireNonNull(adjustedDueDate, "adjustedDueDate");
            if (extraTerms != null) throw new IllegalArgumentException("Điều chỉnh kỳ hạn không nhận extraTerms");
        } else {
            if (adjustedDueDate != null) throw new IllegalArgumentException("Gia hạn không nhận adjustedDueDate");
            if (extraTerms == null || extraTerms <= 0) throw new IllegalArgumentException("extraTerms phải dương");
            value.extraTerms = extraTerms;
        }
        value.status = LoanRescheduleStatus.PENDING_REVIEW;
        value.attemptCount = 0;
        value.createdAt = Objects.requireNonNull(now, "now");
        value.updatedAt = now;
        return value;
    }

    public void approve(String actorId, String comment, String decisionKey, Instant now) {
        if (status != LoanRescheduleStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Yêu cầu không còn chờ duyệt");
        }
        adminId = requireText(actorId, "actorId", 100);
        decisionComment = optionalText(comment, 500);
        decisionIdempotencyKey = requireText(decisionKey, "decisionKey", 150);
        decidedAt = now;
        status = LoanRescheduleStatus.CREATE_PENDING;
        nextAttemptAt = now;
        updatedAt = now;
    }

    public void reject(String actorId, String comment, String decisionKey, Instant now) {
        if (status != LoanRescheduleStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Yêu cầu không còn chờ duyệt");
        }
        adminId = requireText(actorId, "actorId", 100);
        decisionComment = requireText(comment, "comment", 500);
        decisionIdempotencyKey = requireText(decisionKey, "decisionKey", 150);
        decidedAt = now;
        status = LoanRescheduleStatus.REJECTED;
        nextAttemptAt = null;
        updatedAt = now;
    }

    /** Claim chỉ đổi local state; worker phải kết thúc transaction trước khi gọi HTTP. */
    public LoanRescheduleStep claim(Instant now, Duration lease) {
        Objects.requireNonNull(lease, "lease");
        LoanRescheduleStep step;
        if (status == LoanRescheduleStatus.CREATE_PENDING
                || (status == LoanRescheduleStatus.RECONCILIATION_REQUIRED && fineractRescheduleId == null)
                || (status == LoanRescheduleStatus.CREATING && leaseExpired(now, lease))) {
            status = LoanRescheduleStatus.CREATING;
            step = LoanRescheduleStep.CREATE;
        } else if (status == LoanRescheduleStatus.APPROVAL_PENDING
                || (status == LoanRescheduleStatus.RECONCILIATION_REQUIRED && fineractRescheduleId != null)
                || (status == LoanRescheduleStatus.APPROVING && leaseExpired(now, lease))) {
            status = LoanRescheduleStatus.APPROVING;
            step = LoanRescheduleStep.APPROVE;
        } else {
            return null;
        }
        attemptCount++;
        processingStartedAt = now;
        nextAttemptAt = null;
        updatedAt = now;
        return step;
    }

    public void coreCreated(Long coreRequestId, Instant now) {
        if (status != LoanRescheduleStatus.CREATING) throw new IllegalStateException("Request chưa ở bước create");
        fineractRescheduleId = Objects.requireNonNull(coreRequestId, "coreRequestId");
        status = LoanRescheduleStatus.APPROVAL_PENDING;
        processingStartedAt = null;
        nextAttemptAt = now;
        lastErrorCode = null;
        updatedAt = now;
    }

    public void complete(LocalDate maturityDate, Instant now) {
        if (status != LoanRescheduleStatus.APPROVING) throw new IllegalStateException("Request chưa ở bước approve");
        newMaturityDate = maturityDate;
        status = LoanRescheduleStatus.COMPLETED;
        processingStartedAt = null;
        nextAttemptAt = null;
        lastErrorCode = null;
        updatedAt = now;
    }

    public void fail(String code, boolean retryable, int maxAttempts, Instant retryAt, Instant now) {
        if (status != LoanRescheduleStatus.CREATING && status != LoanRescheduleStatus.APPROVING) {
            throw new IllegalStateException("Request không ở bước external call");
        }
        lastErrorCode = requireText(code, "code", 100);
        processingStartedAt = null;
        if (retryable && attemptCount < maxAttempts) {
            status = LoanRescheduleStatus.RECONCILIATION_REQUIRED;
            nextAttemptAt = Objects.requireNonNull(retryAt, "retryAt");
        } else {
            status = LoanRescheduleStatus.MANUAL_REVIEW;
            nextAttemptAt = null;
        }
        updatedAt = now;
    }

    public boolean sameDecision(String decisionKey) {
        return decisionIdempotencyKey != null && decisionIdempotencyKey.equals(decisionKey);
    }

    public boolean due(Instant now, Duration lease) {
        if (status == LoanRescheduleStatus.CREATE_PENDING || status == LoanRescheduleStatus.APPROVAL_PENDING) return true;
        if (status == LoanRescheduleStatus.RECONCILIATION_REQUIRED) {
            return nextAttemptAt != null && !nextAttemptAt.isAfter(now);
        }
        return (status == LoanRescheduleStatus.CREATING || status == LoanRescheduleStatus.APPROVING)
                && leaseExpired(now, lease);
    }

    private boolean leaseExpired(Instant now, Duration lease) {
        return processingStartedAt != null && !processingStartedAt.plus(lease).isAfter(now);
    }

    private static String requireHash(String value, String field) {
        String normalized = requireText(value, field, 64);
        if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " không phải SHA-256");
        return normalized;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " không được trống");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " vượt quá " + maxLength + " ký tự");
        return normalized;
    }

    private static String optionalText(String value, int maxLength) {
        return value == null || value.isBlank() ? null : requireText(value, "comment", maxLength);
    }
}
