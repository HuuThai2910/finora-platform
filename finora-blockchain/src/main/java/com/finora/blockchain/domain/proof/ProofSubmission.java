package com.finora.blockchain.domain.proof;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "blockchain_proof_submissions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProofSubmission {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SOURCE_SERVICE = Pattern.compile("^finora-[a-z0-9-]+$");
    // Cùng quy tắc với chaincode finora-ledger: aggregate chỉ là mã nghiệp vụ, không chứa khoảng trắng/chữ có dấu.
    private static final Pattern AGGREGATE_TYPE = Pattern.compile("^[A-Z][A-Za-z0-9]{1,49}$");
    private static final Pattern AGGREGATE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$");
    private static final Pattern EVENT_TYPE = Pattern.compile("^[A-Z][A-Za-z0-9]{1,79}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proof_id", nullable = false, unique = true, updatable = false)
    private UUID proofId;

    @Column(name = "source_service", nullable = false, length = 50, updatable = false)
    private String sourceService;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "source_event_type", length = 80, updatable = false)
    private String sourceEventType;

    @Column(name = "source_event_version", updatable = false)
    private Integer sourceEventVersion;

    @Column(name = "source_occurred_at", updatable = false)
    private Instant sourceOccurredAt;

    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100, updatable = false)
    private String aggregateId;

    @Column(name = "loan_application_id", updatable = false)
    private Long loanApplicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "proof_type", nullable = false, length = 50, updatable = false)
    private ProofType proofType;

    @Column(name = "payload_hash", nullable = false, length = 64, updatable = false)
    private String payloadHash;

    @Column(name = "payload_version", nullable = false, updatable = false)
    private Integer payloadVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private ProofProviderType provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProofSubmissionStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processing_token")
    private UUID processingToken;

    @Column(name = "provider_transaction_id", length = 200)
    private String providerTransactionId;

    @Column(name = "provider_block_reference", length = 200)
    private String providerBlockReference;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "last_error_code", length = 60)
    private String lastErrorCode;

    @Column(name = "last_error_detail", length = 500)
    private String lastErrorDetail;

    @Enumerated(EnumType.STRING)
    @Column(name = "chain_check_status", length = 30)
    private ProofChainCheckStatus chainCheckStatus;

    @Column(name = "chain_checked_at")
    private Instant chainCheckedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static ProofSubmission register(
            UUID proofId,
            ProofSource source,
            ProofSubject subject,
            String payloadHash,
            int payloadVersion,
            ProofProviderType provider,
            Instant now
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(subject, "subject");
        ProofHashScheme.fromVersion(payloadVersion);
        String normalizedService = requireText(source.service(), "sourceService", 50);
        if (!SOURCE_SERVICE.matcher(normalizedService).matches()) {
            throw new IllegalArgumentException("sourceService phải theo mẫu finora-<service>");
        }
        String normalizedHash = requireText(payloadHash, "payloadHash", 64).toLowerCase();
        if (!SHA_256.matcher(normalizedHash).matches()) {
            throw new IllegalArgumentException("payloadHash phải là SHA-256 lowercase gồm 64 ký tự hex");
        }
        String aggregateType = requireText(subject.aggregateType(), "aggregateType", 50);
        if (!AGGREGATE_TYPE.matcher(aggregateType).matches()) {
            throw new IllegalArgumentException("aggregateType phải là tên aggregate dạng PascalCase");
        }
        String aggregateId = requireText(subject.aggregateId(), "aggregateId", 100);
        if (!AGGREGATE_ID.matcher(aggregateId).matches()) {
            throw new IllegalArgumentException("aggregateId chỉ nhận mã nghiệp vụ (chữ, số, . _ : -)");
        }
        String eventType = optionalText(source.eventType(), 80);
        if (eventType != null && !EVENT_TYPE.matcher(eventType).matches()) {
            throw new IllegalArgumentException("sourceEventType không hợp lệ");
        }
        if (source.eventVersion() != null && source.eventVersion() < 1) {
            throw new IllegalArgumentException("sourceEventVersion phải dương");
        }
        if (subject.loanApplicationId() != null && subject.loanApplicationId() < 1) {
            throw new IllegalArgumentException("loanApplicationId phải dương");
        }
        ProofSubmission submission = new ProofSubmission();
        submission.proofId = Objects.requireNonNull(proofId, "proofId");
        submission.sourceService = normalizedService;
        submission.sourceEventId = Objects.requireNonNull(source.eventId(), "sourceEventId");
        submission.sourceEventType = eventType;
        submission.sourceEventVersion = source.eventVersion();
        submission.sourceOccurredAt = source.occurredAt();
        submission.aggregateType = aggregateType;
        submission.aggregateId = aggregateId;
        submission.loanApplicationId = subject.loanApplicationId();
        submission.proofType = Objects.requireNonNull(subject.proofType(), "proofType");
        submission.payloadHash = normalizedHash;
        submission.payloadVersion = payloadVersion;
        submission.provider = Objects.requireNonNull(provider, "provider");
        submission.status = ProofSubmissionStatus.PENDING;
        submission.attemptCount = 0;
        submission.availableAt = Objects.requireNonNull(now, "now");
        submission.createdAt = now;
        submission.updatedAt = now;
        return submission;
    }

    /** Cùng mốc nghiệp vụ (bất kể hash/version). */
    public boolean sameMilestone(ProofType expectedProofType, String expectedAggregateType, String expectedAggregateId) {
        return proofType == expectedProofType
                && aggregateType.equals(expectedAggregateType)
                && aggregateId.equals(expectedAggregateId);
    }

    /** Cùng mốc và cùng nội dung đã hash theo cùng scheme. */
    public boolean sameContent(ProofSubmission other) {
        return sameMilestone(other.proofType, other.aggregateType, other.aggregateId)
                && payloadHash.equals(other.payloadHash)
                && payloadVersion.equals(other.payloadVersion);
    }

    public ProofHashScheme hashScheme() {
        return ProofHashScheme.fromVersion(payloadVersion);
    }

    /** Receipt thật nằm trên Fabric; receipt MOCK không bao giờ được trình bày như vậy (LEGAL-DATA-01). */
    public boolean isOnChain() {
        return provider == ProofProviderType.HYPERLEDGER_FABRIC && status == ProofSubmissionStatus.CONFIRMED;
    }

    /** Claim có thời hạn để node khác tiếp quản khi process dừng giữa lúc gọi provider. */
    public boolean claim(Instant now, Instant leaseExpiredBefore, UUID claimToken) {
        boolean due = status == ProofSubmissionStatus.PENDING && !availableAt.isAfter(now);
        boolean abandoned = status == ProofSubmissionStatus.PROCESSING
                && processingStartedAt != null
                && !processingStartedAt.isAfter(leaseExpiredBefore);
        if (!due && !abandoned) {
            return false;
        }
        status = ProofSubmissionStatus.PROCESSING;
        attemptCount += 1;
        processingStartedAt = now;
        processingToken = Objects.requireNonNull(claimToken, "claimToken");
        updatedAt = now;
        return true;
    }

    public void confirm(UUID claimToken, String transactionId, String blockReference, Instant now) {
        requireClaim(claimToken);
        String normalizedTransaction = requireText(transactionId, "transactionId", 200);
        String normalizedBlock = optionalText(blockReference, 200);
        if (!ProofReceiptFormat.matches(provider, normalizedTransaction, normalizedBlock)) {
            throw new IllegalStateException("Receipt không đúng định dạng của provider " + provider);
        }
        providerTransactionId = normalizedTransaction;
        providerBlockReference = normalizedBlock;
        status = ProofSubmissionStatus.CONFIRMED;
        processingStartedAt = null;
        processingToken = null;
        confirmedAt = now;
        lastErrorCode = null;
        lastErrorDetail = null;
        updatedAt = now;
    }

    public void fail(
            UUID claimToken,
            String errorCode,
            String errorDetail,
            boolean retryable,
            int maximumAttempts,
            Instant nextAttemptAt,
            Instant now
    ) {
        requireClaim(claimToken);
        lastErrorCode = requireText(errorCode, "errorCode", 60);
        lastErrorDetail = optionalText(errorDetail, 500);
        processingToken = null;
        if (!retryable || attemptCount >= maximumAttempts) {
            status = ProofSubmissionStatus.DEAD_LETTER;
            processingStartedAt = null;
        } else {
            status = ProofSubmissionStatus.PENDING;
            availableAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
            processingStartedAt = null;
        }
        updatedAt = now;
    }

    /**
     * Vận hành đưa proof đã vào DEAD_LETTER về hàng đợi sau khi sửa nguyên nhân (Fabric/identity). Lỗi cuối được giữ
     * để còn dấu vết; số lần thử đếm lại từ đầu.
     */
    public void requeue(Instant now) {
        if (status != ProofSubmissionStatus.DEAD_LETTER) {
            throw new IllegalStateException("Chỉ proof DEAD_LETTER mới được đưa lại hàng đợi");
        }
        status = ProofSubmissionStatus.PENDING;
        attemptCount = 0;
        availableAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
    }

    private void requireClaim(UUID claimToken) {
        if (status != ProofSubmissionStatus.PROCESSING || !Objects.equals(processingToken, claimToken)) {
            throw new IllegalStateException("Proof submission không còn thuộc claim hiện tại");
        }
    }

    private static String requireText(String value, String field, int maximumLength) {
        String normalized = optionalText(value, maximumLength);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        return normalized;
    }

    private static String optionalText(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("Giá trị vượt quá " + maximumLength + " ký tự");
        }
        return normalized;
    }
}
