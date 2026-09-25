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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proof_id", nullable = false, unique = true, updatable = false)
    private UUID proofId;

    @Column(name = "source_service", nullable = false, length = 50, updatable = false)
    private String sourceService;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100, updatable = false)
    private String aggregateId;

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

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static ProofSubmission register(
            UUID proofId,
            String sourceService,
            UUID sourceEventId,
            String aggregateType,
            String aggregateId,
            ProofType proofType,
            String payloadHash,
            int payloadVersion,
            ProofProviderType provider,
            Instant now
    ) {
        if (payloadVersion < 1) {
            throw new IllegalArgumentException("payloadVersion phải dương");
        }
        String normalizedService = requireText(sourceService, "sourceService", 50);
        if (!SOURCE_SERVICE.matcher(normalizedService).matches()) {
            throw new IllegalArgumentException("sourceService phải theo mẫu finora-<service>");
        }
        String normalizedHash = requireText(payloadHash, "payloadHash", 64).toLowerCase();
        if (!SHA_256.matcher(normalizedHash).matches()) {
            throw new IllegalArgumentException("payloadHash phải là SHA-256 lowercase gồm 64 ký tự hex");
        }
        ProofSubmission submission = new ProofSubmission();
        submission.proofId = Objects.requireNonNull(proofId, "proofId");
        submission.sourceService = normalizedService;
        submission.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
        submission.aggregateType = requireText(aggregateType, "aggregateType", 50);
        submission.aggregateId = requireText(aggregateId, "aggregateId", 100);
        submission.proofType = Objects.requireNonNull(proofType, "proofType");
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

    public boolean matches(
            String expectedAggregateType,
            String expectedAggregateId,
            ProofType expectedProofType,
            String expectedPayloadHash,
            int expectedPayloadVersion
    ) {
        return aggregateType.equals(expectedAggregateType)
                && aggregateId.equals(expectedAggregateId)
                && proofType == expectedProofType
                && payloadHash.equals(expectedPayloadHash)
                && payloadVersion.equals(expectedPayloadVersion);
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
        providerTransactionId = requireText(transactionId, "transactionId", 200);
        providerBlockReference = optionalText(blockReference, 200);
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
