package com.finora.investment.domain.restructure;

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

/** Một phiếu đồng ý bất biến theo chủ Note tại thời điểm FINORA gửi đề nghị cơ cấu. */
@Entity
@Table(name = "investment_reschedule_consents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvestorRescheduleConsent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "request_id", nullable = false, updatable = false) private UUID requestId;
    @Column(name = "loan_application_id", nullable = false, updatable = false) private Long loanApplicationId;
    @Column(name = "loan_number", nullable = false, length = 50, updatable = false) private String loanNumber;
    @Column(name = "request_type", nullable = false, length = 40, updatable = false) private String requestType;
    @Column(name = "reschedule_from_date", nullable = false, updatable = false) private LocalDate rescheduleFromDate;
    @Column(name = "adjusted_due_date", updatable = false) private LocalDate adjustedDueDate;
    @Column(name = "extra_terms", updatable = false) private Integer extraTerms;
    @Column(name = "original_maturity_date", updatable = false) private LocalDate originalMaturityDate;
    @Column(name = "investor_id", nullable = false, length = 100, updatable = false) private String investorId;
    @Column(name = "outstanding_principal", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal outstandingPrincipal;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private InvestorRescheduleConsentStatus status;
    @Column(name = "decision_comment", length = 500) private String decisionComment;
    @Column(name = "decision_idempotency_key", length = 150) private String decisionIdempotencyKey;
    @Column(name = "decided_at") private Instant decidedAt;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static InvestorRescheduleConsent pending(UUID requestId, Long loanApplicationId, String loanNumber,
            String requestType, LocalDate rescheduleFromDate, LocalDate adjustedDueDate, Integer extraTerms,
            LocalDate originalMaturityDate, String investorId, BigDecimal outstandingPrincipal, Instant now) {
        InvestorRescheduleConsent value = new InvestorRescheduleConsent();
        value.requestId = Objects.requireNonNull(requestId, "requestId");
        value.loanApplicationId = Objects.requireNonNull(loanApplicationId, "loanApplicationId");
        value.loanNumber = text(loanNumber, "loanNumber", 50);
        value.requestType = text(requestType, "requestType", 40);
        value.rescheduleFromDate = Objects.requireNonNull(rescheduleFromDate, "rescheduleFromDate");
        value.adjustedDueDate = adjustedDueDate;
        value.extraTerms = extraTerms;
        value.originalMaturityDate = originalMaturityDate;
        value.investorId = text(investorId, "investorId", 100);
        value.outstandingPrincipal = Objects.requireNonNull(outstandingPrincipal, "outstandingPrincipal")
                .setScale(2, RoundingMode.HALF_UP);
        if (value.outstandingPrincipal.signum() <= 0) {
            throw new IllegalArgumentException("Dư nợ biểu quyết phải dương");
        }
        value.status = InvestorRescheduleConsentStatus.PENDING;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public void approve(String key, String comment, Instant now) {
        decide(InvestorRescheduleConsentStatus.APPROVED, key, comment, now);
    }

    public void reject(String key, String comment, Instant now) {
        decide(InvestorRescheduleConsentStatus.REJECTED, key, comment, now);
    }

    public void cancel(Instant now) {
        if (status == InvestorRescheduleConsentStatus.PENDING) {
            status = InvestorRescheduleConsentStatus.CANCELLED;
            updatedAt = now;
        }
    }

    public boolean sameDecision(String key) {
        return decisionIdempotencyKey != null && decisionIdempotencyKey.equals(key);
    }

    private void decide(InvestorRescheduleConsentStatus decision, String key, String comment, Instant now) {
        if (status != InvestorRescheduleConsentStatus.PENDING) {
            throw new IllegalStateException("Phiếu đồng ý không còn chờ quyết định");
        }
        decisionIdempotencyKey = text(key, "idempotencyKey", 150);
        decisionComment = comment == null || comment.isBlank() ? null : text(comment, "comment", 500);
        status = decision;
        decidedAt = now;
        updatedAt = now;
    }

    private static String text(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " không được trống");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(field + " vượt quá " + max + " ký tự");
        return normalized;
    }
}
