package com.finora.loan.domain.contract;

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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Một bên ký trên cùng immutable PDF/hash; không tạo một hợp đồng riêng cho từng lender. */
@Entity
@Table(name = "loan_contract_parties")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContractParty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private Long contractId;

    @Column(name = "commitment_id", updatable = false)
    private Long commitmentId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "party_type", nullable = false, length = 20, updatable = false)
    private ContractPartyType partyType;

    @Column(name = "party_id", nullable = false, length = 100, updatable = false)
    private String partyId;

    @Column(name = "allocation_amount", precision = 18, scale = 2, updatable = false)
    private BigDecimal allocationAmount;

    @Column(name = "allocation_share_percent", precision = 9, scale = 6, updatable = false)
    private BigDecimal allocationSharePercent;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private ContractPartyStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "signature_method", length = 30)
    private SignatureMethod signatureMethod;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "signature_provider", length = 30)
    private SignatureProviderType signatureProvider;

    @Column(name = "signature_transaction_id", length = 150)
    private String signatureTransactionId;

    @Column(name = "signature_document_id", length = 100)
    private String signatureDocumentId;

    @Column(name = "signature_requested_at")
    private Instant signatureRequestedAt;

    @Column(name = "signature_evidence_hash", length = 64)
    private String signatureEvidenceHash;

    @Column(name = "idempotency_key", length = 150)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static ContractParty borrower(Long contractId, String borrowerId, Instant now) {
        return create(contractId, null, ContractPartyType.BORROWER, borrowerId, null, null, now);
    }

    public static ContractParty lender(
            Long contractId,
            Long commitmentId,
            String investorId,
            BigDecimal amount,
            BigDecimal sharePercent,
            Instant now
    ) {
        if (commitmentId == null) {
            throw new IllegalArgumentException("commitmentId không được null");
        }
        BigDecimal normalizedAmount = Objects.requireNonNull(amount, "amount").setScale(2, RoundingMode.HALF_UP);
        BigDecimal normalizedShare = Objects.requireNonNull(sharePercent, "sharePercent")
                .setScale(6, RoundingMode.HALF_UP);
        if (normalizedAmount.signum() <= 0 || normalizedShare.signum() <= 0) {
            throw new IllegalArgumentException("Phần phân bổ lender phải dương");
        }
        return create(contractId, commitmentId, ContractPartyType.LENDER, investorId,
                normalizedAmount, normalizedShare, now);
    }

    private static ContractParty create(
            Long contractId,
            Long commitmentId,
            ContractPartyType type,
            String partyId,
            BigDecimal amount,
            BigDecimal share,
            Instant now
    ) {
        ContractParty party = new ContractParty();
        party.contractId = Objects.requireNonNull(contractId, "contractId");
        party.commitmentId = commitmentId;
        party.partyType = Objects.requireNonNull(type, "partyType");
        party.partyId = requireText(partyId, "partyId");
        party.allocationAmount = amount;
        party.allocationSharePercent = share;
        party.status = ContractPartyStatus.PENDING_SIGNATURE;
        party.createdAt = Objects.requireNonNull(now, "now");
        party.updatedAt = now;
        return party;
    }

    public boolean signMock(
            String transactionId,
            String evidenceHash,
            String requestedIdempotencyKey,
            String requestedHash,
            String actorId,
            Instant now
    ) {
        return recordSignature(
                SignatureMethod.CLICK_WRAP_MVP, SignatureProviderType.MOCK,
                transactionId, evidenceHash, requestedIdempotencyKey,
                requestedHash, actorId, now);
    }

    /**
     * Ghi nhận yêu cầu SmartCA trước khi gọi VNPT để retry không tạo giao dịch ký thứ hai.
     */
    public boolean beginDigitalSignature(
            String transactionId,
            String documentId,
            String requestedIdempotencyKey,
            String requestedHash,
            String actorId,
            Instant now
    ) {
        requireOwner(actorId);
        if (status == ContractPartyStatus.SIGNING) {
            if (Objects.equals(idempotencyKey, requestedIdempotencyKey)
                    && Objects.equals(requestHash, requestedHash)
                    && Objects.equals(signatureTransactionId, transactionId)
                    && Objects.equals(signatureDocumentId, documentId)) {
                return false;
            }
            throw LoanDomainException.conflict(
                    "CONTRACT_PARTY_SIGNATURE_IN_PROGRESS",
                    "Bên tham gia đang ký bằng một yêu cầu SmartCA khác");
        }
        if (status != ContractPartyStatus.PENDING_SIGNATURE) {
            throw LoanDomainException.conflict(
                    "CONTRACT_PARTY_NOT_PENDING", "Bên tham gia không còn ở trạng thái chờ ký");
        }
        signatureMethod = SignatureMethod.VNPT_SMART_CA;
        signatureProvider = SignatureProviderType.VNPT_SMART_CA;
        signatureTransactionId = requireText(transactionId, "transactionId");
        signatureDocumentId = requireText(documentId, "documentId");
        signatureRequestedAt = Objects.requireNonNull(now, "now");
        idempotencyKey = requireText(requestedIdempotencyKey, "idempotencyKey");
        requestHash = requireHash(requestedHash, "requestHash");
        status = ContractPartyStatus.SIGNING;
        updatedAt = now;
        return true;
    }

    /** Hoàn tất đúng giao dịch SmartCA đã mở cho bên ký này. */
    public boolean completeDigitalSignature(
            String transactionId,
            String documentId,
            String evidenceHash,
            String actorId,
            Instant now
    ) {
        requireOwner(actorId);
        if (status == ContractPartyStatus.SIGNED) {
            if (Objects.equals(signatureTransactionId, transactionId)
                    && Objects.equals(signatureDocumentId, documentId)
                    && Objects.equals(signatureEvidenceHash, evidenceHash)) {
                return false;
            }
            throw LoanDomainException.conflict(
                    "CONTRACT_PARTY_ALREADY_SIGNED", "Bên tham gia đã ký bằng một giao dịch khác");
        }
        if (status != ContractPartyStatus.SIGNING
                || signatureProvider != SignatureProviderType.VNPT_SMART_CA
                || signatureMethod != SignatureMethod.VNPT_SMART_CA) {
            throw LoanDomainException.conflict(
                    "CONTRACT_PARTY_SIGNATURE_NOT_STARTED", "Chưa có yêu cầu SmartCA đang chờ xác nhận");
        }
        if (!Objects.equals(signatureTransactionId, transactionId)
                || !Objects.equals(signatureDocumentId, documentId)) {
            throw LoanDomainException.conflict(
                    "CONTRACT_PARTY_SIGNATURE_MISMATCH", "Kết quả SmartCA không khớp yêu cầu đã mở");
        }
        signatureEvidenceHash = requireHash(evidenceHash, "evidenceHash");
        signedAt = Objects.requireNonNull(now, "now");
        status = ContractPartyStatus.SIGNED;
        updatedAt = now;
        return true;
    }

    /** Cho phép thử lại sau khi người dùng từ chối/hủy giao dịch trên SmartCA. */
    public void resetRejectedDigitalSignature(String actorId, Instant now) {
        requireOwner(actorId);
        if (status != ContractPartyStatus.SIGNING) {
            return;
        }
        status = ContractPartyStatus.PENDING_SIGNATURE;
        signatureMethod = null;
        signatureProvider = null;
        signatureTransactionId = null;
        signatureDocumentId = null;
        signatureRequestedAt = null;
        signatureEvidenceHash = null;
        idempotencyKey = null;
        requestHash = null;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean recordSignature(
            SignatureMethod method,
            SignatureProviderType provider,
            String transactionId,
            String evidenceHash,
            String requestedIdempotencyKey,
            String requestedHash,
            String actorId,
            Instant now
    ) {
        requireOwner(actorId);
        if (status == ContractPartyStatus.SIGNED) {
            if (Objects.equals(idempotencyKey, requestedIdempotencyKey)
                    && Objects.equals(requestHash, requestedHash)) {
                return false;
            }
            throw LoanDomainException.conflict(
                    "CONTRACT_PARTY_ALREADY_SIGNED", "Bên tham gia đã ký với một yêu cầu khác");
        }
        if (status != ContractPartyStatus.PENDING_SIGNATURE) {
            throw LoanDomainException.conflict(
                    "CONTRACT_PARTY_NOT_PENDING", "Bên tham gia không còn ở trạng thái chờ ký");
        }
        if (provider != SignatureProviderType.MOCK || method != SignatureMethod.CLICK_WRAP_MVP) {
            throw new IllegalArgumentException(
                    "recordSignature chỉ dùng cho click-wrap; SmartCA phải đi qua trạng thái async");
        }
        signatureMethod = Objects.requireNonNull(method, "method");
        signatureProvider = Objects.requireNonNull(provider, "provider");
        signatureTransactionId = requireText(transactionId, "transactionId");
        signatureEvidenceHash = requireHash(evidenceHash, "evidenceHash");
        idempotencyKey = requireText(requestedIdempotencyKey, "idempotencyKey");
        requestHash = requireHash(requestedHash, "requestHash");
        signedAt = Objects.requireNonNull(now, "now");
        status = ContractPartyStatus.SIGNED;
        updatedAt = now;
        return true;
    }

    /**
     * Đồng bộ bằng chứng VNPT của borrower party sau khi LoanContract đã hoàn tất
     * luồng SmartCA async. Trạng thái chờ của borrower vẫn do LoanContract sở hữu.
     */
    public boolean recordCompletedDigitalSignature(
            String transactionId,
            String documentId,
            Instant requestedAt,
            String evidenceHash,
            String requestedIdempotencyKey,
            String requestedHash,
            String actorId,
            Instant now
    ) {
        requireOwner(actorId);
        if (status == ContractPartyStatus.SIGNED) {
            if (Objects.equals(idempotencyKey, requestedIdempotencyKey)
                    && Objects.equals(requestHash, requestedHash)) {
                return false;
            }
            throw LoanDomainException.conflict(
                    "CONTRACT_PARTY_ALREADY_SIGNED", "Bên tham gia đã ký với một yêu cầu khác");
        }
        if (status != ContractPartyStatus.PENDING_SIGNATURE) {
            throw LoanDomainException.conflict(
                    "CONTRACT_PARTY_NOT_PENDING", "Bên tham gia không còn ở trạng thái chờ ký");
        }
        signatureMethod = SignatureMethod.VNPT_SMART_CA;
        signatureProvider = SignatureProviderType.VNPT_SMART_CA;
        signatureTransactionId = requireText(transactionId, "transactionId");
        signatureDocumentId = requireText(documentId, "documentId");
        signatureRequestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        signatureEvidenceHash = requireHash(evidenceHash, "evidenceHash");
        idempotencyKey = requireText(requestedIdempotencyKey, "idempotencyKey");
        requestHash = requireHash(requestedHash, "requestHash");
        signedAt = Objects.requireNonNull(now, "now");
        status = ContractPartyStatus.SIGNED;
        updatedAt = now;
        return true;
    }

    public void requireOwner(String actorId) {
        if (!partyId.equals(actorId)) {
            throw LoanDomainException.forbidden(
                    "CONTRACT_PARTY_ACCESS_DENIED", "Bạn không phải bên ký của phần vốn này");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        return value.trim();
    }

    private static String requireHash(String value, String field) {
        String hash = requireText(value, field);
        if (!hash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(field + " phải là SHA-256 chữ thường");
        }
        return hash;
    }
}
