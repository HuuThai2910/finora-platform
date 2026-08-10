package com.finora.loan.domain.contract;

import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.exception.LoanDomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "loan_contracts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanContract {

    public static final String DOCUMENT_CONTENT_TYPE = "text/plain; charset=UTF-8";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_number", nullable = false, length = 50, unique = true, updatable = false)
    private String contractNumber;

    @Column(name = "application_id", nullable = false, unique = true, updatable = false)
    private Long applicationId;

    @Column(name = "borrower_id", nullable = false, length = 100, updatable = false)
    private String borrowerId;

    @Column(name = "principal_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal principalAmount;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "term_months", nullable = false, updatable = false)
    private Integer termMonths;

    @Column(name = "annual_interest_rate", nullable = false, precision = 7, scale = 4, updatable = false)
    private BigDecimal annualInterestRate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "repayment_method", nullable = false, length = 30, updatable = false)
    private RepaymentMethod repaymentMethod;

    @Column(name = "calculation_snapshot_id", nullable = false, updatable = false)
    private Long calculationSnapshotId;

    @Column(name = "total_interest", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalInterest;

    @Column(name = "total_fees", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalFees;

    @Column(name = "total_penalties", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalPenalties;

    @Column(name = "total_repayment", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalRepayment;

    @Column(name = "first_installment", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal firstInstallment;

    @Column(name = "maximum_installment", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal maximumInstallment;

    @Column(name = "schedule_response_hash", nullable = false, length = 64, updatable = false)
    private String scheduleResponseHash;

    @Column(name = "expected_disbursement_date", nullable = false, updatable = false)
    private LocalDate expectedDisbursementDate;

    @Column(name = "terms_version", nullable = false, length = 30, updatable = false)
    private String termsVersion;

    @Column(name = "document_version", nullable = false, length = 30, updatable = false)
    private String documentVersion;

    @Column(name = "document_content", nullable = false, columnDefinition = "text", updatable = false)
    private String documentContent;

    @Column(name = "document_content_type", nullable = false, length = 50, updatable = false)
    private String documentContentType;

    @Column(name = "document_hash", nullable = false, length = 64, updatable = false)
    private String documentHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private LoanContractStatus status;

    @Column(name = "consent_idempotency_key", length = 150)
    private String consentIdempotencyKey;

    @Column(name = "consent_request_hash", length = 64)
    private String consentRequestHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "consent_action", length = 20)
    private ConsentAction consentAction;

    @Column(name = "signed_by", length = 100)
    private String signedBy;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "signature_method", length = 30)
    private SignatureMethod signatureMethod;

    @Column(name = "declined_by", length = 100)
    private String declinedBy;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "decline_reason_code", length = 50)
    private ContractDeclineReasonCode declineReasonCode;

    @Column(name = "decline_reason_detail", length = 1000)
    private String declineReasonDetail;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "effective_at")
    private Instant effectiveAt;

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

    /** Tạo Contract từ exact terms và schedule submission; không tự tính lại bất kỳ giá trị tiền nào. */
    public static LoanContract create(
            String contractNumber,
            Long applicationId,
            String borrowerId,
            LoanContractTerms terms,
            String termsVersion,
            String documentVersion,
            String documentContent,
            String documentHash,
            Instant expiresAt,
            String actorId,
            Instant now
    ) {
        Objects.requireNonNull(terms, "terms");
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw LoanDomainException.invalidInput("CONTRACT_EXPIRY_INVALID", "Hạn ký phải ở tương lai");
        }
        LoanContract contract = new LoanContract();
        contract.contractNumber = requireText(contractNumber, "contractNumber");
        contract.applicationId = Objects.requireNonNull(applicationId, "applicationId");
        contract.borrowerId = requireText(borrowerId, "borrowerId");
        contract.principalAmount = money(terms.principalAmount(), "principalAmount");
        if (contract.principalAmount.signum() == 0) {
            throw new IllegalArgumentException("principalAmount phải dương");
        }
        contract.termMonths = positive(terms.termMonths(), "termMonths");
        contract.annualInterestRate = Objects.requireNonNull(terms.annualInterestRate(), "annualInterestRate")
                .setScale(4, RoundingMode.HALF_UP);
        contract.repaymentMethod = Objects.requireNonNull(terms.repaymentMethod(), "repaymentMethod");
        contract.calculationSnapshotId = Objects.requireNonNull(terms.calculationSnapshotId(), "calculationSnapshotId");
        contract.totalInterest = money(terms.totalInterest(), "totalInterest");
        contract.totalFees = money(terms.totalFees(), "totalFees");
        contract.totalPenalties = money(terms.totalPenalties(), "totalPenalties");
        contract.totalRepayment = money(terms.totalRepayment(), "totalRepayment");
        contract.firstInstallment = money(terms.firstInstallment(), "firstInstallment");
        contract.maximumInstallment = money(terms.maximumInstallment(), "maximumInstallment");
        contract.scheduleResponseHash = requireText(terms.scheduleResponseHash(), "scheduleResponseHash");
        contract.expectedDisbursementDate = Objects.requireNonNull(
                terms.expectedDisbursementDate(), "expectedDisbursementDate");
        contract.termsVersion = requireText(termsVersion, "termsVersion");
        contract.documentVersion = requireText(documentVersion, "documentVersion");
        contract.documentContent = requireText(documentContent, "documentContent");
        contract.documentContentType = DOCUMENT_CONTENT_TYPE;
        contract.documentHash = requireText(documentHash, "documentHash");
        contract.status = LoanContractStatus.PENDING_SIGNATURE;
        contract.expiresAt = expiresAt;
        contract.createdBy = requireText(actorId, "actorId");
        contract.updatedBy = contract.createdBy;
        contract.createdAt = now;
        contract.updatedAt = now;
        return contract;
    }

    /** Server đối chiếu version, owner, expiry và hash để consent luôn gắn đúng văn bản borrower đã xem. */
    public void sign(
            long expectedVersion,
            String expectedDocumentHash,
            SignatureMethod method,
            String idempotencyKey,
            String requestHash,
            String actorId,
            Instant now
    ) {
        requireOwner(actorId);
        requireVersion(expectedVersion);
        requirePending();
        requireNotExpired(now);
        if (!documentHash.equals(expectedDocumentHash)) {
            throw LoanDomainException.conflict(
                    "CONTRACT_CONTENT_MISMATCH",
                    "Nội dung hợp đồng đã xem không khớp phiên bản cần ký"
            );
        }
        if (method != SignatureMethod.CLICK_WRAP_MVP) {
            throw LoanDomainException.invalidInput("SIGNATURE_METHOD_UNSUPPORTED", "Phương thức ký chưa được hỗ trợ");
        }
        status = LoanContractStatus.SIGNED;
        consentIdempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        consentRequestHash = requireText(requestHash, "requestHash");
        consentAction = ConsentAction.SIGN;
        signedBy = actorId;
        signedAt = now;
        signatureMethod = method;
        updatedBy = actorId;
        updatedAt = now;
    }

    /** Borrower chỉ được từ chối Contract còn đang chờ và thuộc chính mình. */
    public void decline(
            long expectedVersion,
            ContractDeclineReasonCode reasonCode,
            String reasonDetail,
            String idempotencyKey,
            String requestHash,
            String actorId,
            Instant now
    ) {
        requireOwner(actorId);
        requireVersion(expectedVersion);
        requirePending();
        requireNotExpired(now);
        status = LoanContractStatus.DECLINED;
        consentIdempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        consentRequestHash = requireText(requestHash, "requestHash");
        consentAction = ConsentAction.DECLINE;
        declinedBy = actorId;
        declinedAt = now;
        declineReasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        declineReasonDetail = normalizeOptional(reasonDetail);
        updatedBy = actorId;
        updatedAt = now;
    }

    /** Expire là transition có điều kiện; worker chạy lại không tạo thêm history hoặc thay đổi terminal state. */
    public boolean expireIfDue(Instant now) {
        if (status != LoanContractStatus.PENDING_SIGNATURE || expiresAt.isAfter(now)) {
            return false;
        }
        status = LoanContractStatus.EXPIRED;
        updatedBy = "SYSTEM";
        updatedAt = now;
        return true;
    }

    public boolean isDueAt(Instant now) {
        return status == LoanContractStatus.PENDING_SIGNATURE && !expiresAt.isAfter(now);
    }

    public boolean isSameConsent(String idempotencyKey, String requestHash, ConsentAction action) {
        return consentIdempotencyKey != null
                && consentIdempotencyKey.equals(idempotencyKey)
                && consentRequestHash.equals(requestHash)
                && consentAction == action;
    }

    public boolean hasConsentKey(String idempotencyKey) {
        return consentIdempotencyKey != null && consentIdempotencyKey.equals(idempotencyKey);
    }

    public void requireOwner(String actorId) {
        if (!borrowerId.equals(actorId)) {
            throw LoanDomainException.forbidden(
                    "LOAN_CONTRACT_ACCESS_DENIED",
                    "Bạn không có quyền truy cập hợp đồng này"
            );
        }
    }

    private void requirePending() {
        if (status != LoanContractStatus.PENDING_SIGNATURE) {
            throw LoanDomainException.conflict(
                    "INVALID_CONTRACT_TRANSITION",
                    "Hợp đồng không còn ở trạng thái chờ ký"
            );
        }
    }

    private void requireNotExpired(Instant now) {
        if (!expiresAt.isAfter(now)) {
            throw LoanDomainException.conflict("CONTRACT_EXPIRED", "Hợp đồng đã hết hạn ký");
        }
    }

    private void requireVersion(long expectedVersion) {
        long currentVersion = version == null ? 0L : version;
        if (currentVersion != expectedVersion) {
            throw LoanDomainException.conflict(
                    "LOAN_CONTRACT_VERSION_CONFLICT",
                    "Hợp đồng đã được cập nhật bởi yêu cầu khác"
            );
        }
    }

    private static BigDecimal money(BigDecimal value, String field) {
        BigDecimal normalized = Objects.requireNonNull(value, field).setScale(2, RoundingMode.HALF_UP);
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(field + " không được âm");
        }
        return normalized;
    }

    private static Integer positive(Integer value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " phải dương");
        }
        return value;
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
