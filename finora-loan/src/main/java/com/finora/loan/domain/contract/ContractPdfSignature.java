package com.finora.loan.domain.contract;

import com.finora.loan.exception.LoanDomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Một lần ký PAdES của một bên trên chuỗi PDF của Contract (R5, D13).
 *
 * <p>Lần ký đi qua hai bước vì provider từ xa (SmartCA) cần người dùng xác nhận trên app: PREPARED giữ bytes đã có
 * chỗ trống chữ ký cùng signed attributes đã gửi đi; khi có giá trị chữ ký, CMS được nhúng đúng vào chỗ trống đó và
 * dòng chuyển SIGNED. Bản SIGNED có số thứ tự {@code sequenceNumber} chính là phiên bản thứ N của tệp hợp đồng;
 * bản sau luôn được chuẩn bị từ đúng bytes của bản trước nên chữ ký trước không bị thay đổi.</p>
 */
@Entity
@Table(name = "loan_contract_signatures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContractPdfSignature {

    public static final String SIGNATURE_FORMAT = "PADES_B_B";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private Long contractId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "signer_type", nullable = false, length = 20, updatable = false)
    private ContractPartyType signerType;

    @Column(name = "signer_id", nullable = false, length = 100, updatable = false)
    private String signerId;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "sequence_number", nullable = false, updatable = false)
    private Integer sequenceNumber;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "attempt_number", nullable = false, updatable = false)
    private Integer attemptNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ContractPdfSignatureStatus status;

    @Column(name = "signature_format", nullable = false, length = 30, updatable = false)
    private String signatureFormat;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "signature_provider", nullable = false, length = 30, updatable = false)
    private SignatureProviderType provider;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "signature_method", nullable = false, length = 30, updatable = false)
    private SignatureMethod method;

    @Column(name = "provider_transaction_id", nullable = false, length = 150, updatable = false)
    private String providerTransactionId;

    @Column(name = "provider_document_id", nullable = false, length = 100, updatable = false)
    private String providerDocumentId;

    @Column(name = "idempotency_key", nullable = false, length = 150, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    /** Hash của bản PDF ngay trước lần ký này (SIGNABLE với chữ ký đầu, bản SIGNED trước với các chữ ký sau). */
    @Column(name = "base_content_hash", nullable = false, length = 64, updatable = false)
    private String baseContentHash;

    @Lob
    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "content_length", nullable = false, updatable = false)
    private Integer contentLength;

    /** Vị trí ký tự {@code <} của /Contents; ByteRange là [0, offset, offset + length, phần còn lại]. */
    @Column(name = "signature_contents_offset", nullable = false, updatable = false)
    private Integer signatureContentsOffset;

    @Column(name = "signature_contents_length", nullable = false, updatable = false)
    private Integer signatureContentsLength;

    /** SHA-256 của phần ByteRange; là thuộc tính message-digest trong CMS. */
    @Column(name = "document_digest", nullable = false, length = 64, updatable = false)
    private String documentDigest;

    @Lob
    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "signed_attributes", nullable = false, updatable = false, columnDefinition = "bytea")
    private byte[] signedAttributes;

    /** SHA-256 của DER signed attributes; đây là hash duy nhất gửi provider ký (sign_type hash). */
    @Column(name = "signed_attributes_digest", nullable = false, length = 64, updatable = false)
    private String signedAttributesDigest;

    /** Thời điểm ký ghi vào khóa /M của chữ ký PDF; PAdES-B-B không dùng thuộc tính signing-time trong CMS. */
    @Column(name = "signing_time", nullable = false, updatable = false)
    private Instant signingTime;

    @Lob
    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, updatable = false, columnDefinition = "bytea")
    private byte[] certificate;

    @Lob
    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "certificate_chain", updatable = false, columnDefinition = "bytea")
    private byte[] certificateChain;

    @Column(name = "certificate_serial", nullable = false, length = 80, updatable = false)
    private String certificateSerial;

    @Column(name = "certificate_sha256", nullable = false, length = 64, updatable = false)
    private String certificateSha256;

    @Column(name = "cms_hash", length = 64)
    private String cmsHash;

    @Column(name = "evidence_hash", length = 64)
    private String evidenceHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "abandon_reason", length = 50)
    private ContractPdfSignatureAbandonReason abandonReason;

    @Column(name = "prepared_at", nullable = false, updatable = false)
    private Instant preparedAt;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "abandoned_at")
    private Instant abandonedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Ghi lần ký vừa chuẩn bị; bytes và signed attributes phải được lưu trước khi gửi hash cho provider. */
    public static ContractPdfSignature prepare(Preparation preparation, Instant now) {
        Objects.requireNonNull(preparation, "preparation");
        ContractPdfSignature signature = new ContractPdfSignature();
        signature.contractId = Objects.requireNonNull(preparation.contractId(), "contractId");
        signature.signerType = Objects.requireNonNull(preparation.signerType(), "signerType");
        signature.signerId = requireText(preparation.signerId(), "signerId");
        signature.sequenceNumber = positive(preparation.sequenceNumber(), "sequenceNumber");
        signature.attemptNumber = positive(preparation.attemptNumber(), "attemptNumber");
        signature.status = ContractPdfSignatureStatus.PREPARED;
        signature.signatureFormat = SIGNATURE_FORMAT;
        signature.provider = Objects.requireNonNull(preparation.provider(), "provider");
        signature.method = Objects.requireNonNull(preparation.method(), "method");
        if ((signature.provider == SignatureProviderType.MOCK && signature.method != SignatureMethod.CLICK_WRAP_MVP)
                || (signature.provider == SignatureProviderType.VNPT_SMART_CA
                && signature.method != SignatureMethod.VNPT_SMART_CA)) {
            throw LoanDomainException.invalidInput(
                    "SIGNATURE_METHOD_PROVIDER_MISMATCH", "Phương thức ký không tương thích provider");
        }
        signature.providerTransactionId = requireText(preparation.providerTransactionId(), "providerTransactionId");
        signature.providerDocumentId = requireText(preparation.providerDocumentId(), "providerDocumentId");
        signature.idempotencyKey = requireText(preparation.idempotencyKey(), "idempotencyKey");
        signature.requestHash = requireHash(preparation.requestHash(), "requestHash");
        signature.baseContentHash = requireHash(preparation.baseContentHash(), "baseContentHash");
        byte[] content = requireBytes(preparation.preparedContent(), "preparedContent");
        int offset = preparation.signatureContentsOffset();
        int length = preparation.signatureContentsLength();
        if (offset <= 0 || length <= 2 || (long) offset + length >= content.length) {
            throw new IllegalArgumentException("Vị trí /Contents nằm ngoài nội dung PDF");
        }
        signature.content = Arrays.copyOf(content, content.length);
        signature.contentHash = requireHash(preparation.preparedContentHash(), "preparedContentHash");
        signature.contentLength = content.length;
        signature.signatureContentsOffset = offset;
        signature.signatureContentsLength = length;
        signature.documentDigest = requireHash(preparation.documentDigest(), "documentDigest");
        byte[] attributes = requireBytes(preparation.signedAttributes(), "signedAttributes");
        signature.signedAttributes = Arrays.copyOf(attributes, attributes.length);
        signature.signedAttributesDigest = requireHash(preparation.signedAttributesDigest(), "signedAttributesDigest");
        signature.signingTime = Objects.requireNonNull(preparation.signingTime(), "signingTime");
        byte[] certificate = requireBytes(preparation.certificate(), "certificate");
        signature.certificate = Arrays.copyOf(certificate, certificate.length);
        byte[] chain = preparation.certificateChain();
        signature.certificateChain = chain == null || chain.length == 0 ? null : Arrays.copyOf(chain, chain.length);
        signature.certificateSerial = requireText(preparation.certificateSerial(), "certificateSerial");
        signature.certificateSha256 = requireHash(preparation.certificateSha256(), "certificateSha256");
        signature.preparedAt = Objects.requireNonNull(now, "now");
        signature.createdAt = now;
        signature.updatedAt = now;
        return signature;
    }

    /**
     * Nhận bytes sau khi nhúng CMS. Ngoài vùng hex của /Contents không được khác một byte nào so với bản đã chuẩn bị,
     * vì ByteRange đã băm và gửi ký chính phần đó; khác đi nghĩa là chữ ký sẽ không còn khớp tài liệu.
     */
    public void markSigned(byte[] signedContent, String signedContentHash, String cms, String evidence, Instant now) {
        requirePrepared();
        byte[] signed = requireBytes(signedContent, "signedContent");
        if (signed.length != contentLength) {
            throw LoanDomainException.conflict(
                    "PADES_SIGNED_CONTENT_MISMATCH", "Bản PDF đã nhúng chữ ký không cùng độ dài với bản đã chuẩn bị");
        }
        int hexStart = signatureContentsOffset + 1;
        int hexEnd = signatureContentsOffset + signatureContentsLength - 1;
        if (!Arrays.equals(signed, 0, hexStart, content, 0, hexStart)
                || !Arrays.equals(signed, hexEnd, signed.length, content, hexEnd, content.length)) {
            throw LoanDomainException.conflict(
                    "PADES_SIGNED_CONTENT_MISMATCH", "Bản PDF đã nhúng chữ ký thay đổi phần đã được băm để ký");
        }
        content = Arrays.copyOf(signed, signed.length);
        contentHash = requireHash(signedContentHash, "signedContentHash");
        cmsHash = requireHash(cms, "cmsHash");
        evidenceHash = requireHash(evidence, "evidenceHash");
        status = ContractPdfSignatureStatus.SIGNED;
        signedAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
    }

    /** Bỏ lần ký chưa hoàn tất; bytes đã chuẩn bị không bao giờ trở thành một phiên bản của hợp đồng. */
    public void abandon(ContractPdfSignatureAbandonReason reason, Instant now) {
        requirePrepared();
        abandonReason = Objects.requireNonNull(reason, "reason");
        status = ContractPdfSignatureStatus.ABANDONED;
        abandonedAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
    }

    public boolean isPrepared() {
        return status == ContractPdfSignatureStatus.PREPARED;
    }

    public boolean isSigned() {
        return status == ContractPdfSignatureStatus.SIGNED;
    }

    public boolean matchesRequest(String requestedIdempotencyKey, String requestedHash) {
        return idempotencyKey.equals(requestedIdempotencyKey) && requestHash.equals(requestedHash);
    }

    /** Phiên ký quá hạn được giải phóng để bên khác ký tiếp; tính từ lúc chuẩn bị bytes, không từ lúc provider nhận. */
    public boolean isSessionExpired(Instant now, Duration timeout) {
        return isPrepared() && !preparedAt.plus(timeout).isAfter(now);
    }

    public byte[] contentCopy() {
        return Arrays.copyOf(content, content.length);
    }

    public byte[] signedAttributesCopy() {
        return Arrays.copyOf(signedAttributes, signedAttributes.length);
    }

    public byte[] certificateCopy() {
        return Arrays.copyOf(certificate, certificate.length);
    }

    public byte[] certificateChainCopy() {
        return certificateChain == null ? new byte[0] : Arrays.copyOf(certificateChain, certificateChain.length);
    }

    private void requirePrepared() {
        if (status != ContractPdfSignatureStatus.PREPARED) {
            throw LoanDomainException.conflict(
                    "PADES_SIGNATURE_NOT_PREPARED", "Lần ký PDF không còn ở trạng thái chờ chữ ký");
        }
    }

    private static Integer positive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " phải dương");
        }
        return value;
    }

    private static byte[] requireBytes(byte[] value, String field) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(field + " không được rỗng");
        }
        return value;
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

    /** Toàn bộ giá trị của bước chuẩn bị; gom thành value object để factory không nhận hơn hai mươi tham số rời. */
    public record Preparation(
            Long contractId,
            ContractPartyType signerType,
            String signerId,
            int sequenceNumber,
            int attemptNumber,
            SignatureProviderType provider,
            SignatureMethod method,
            String providerTransactionId,
            String providerDocumentId,
            String idempotencyKey,
            String requestHash,
            String baseContentHash,
            byte[] preparedContent,
            String preparedContentHash,
            int signatureContentsOffset,
            int signatureContentsLength,
            String documentDigest,
            byte[] signedAttributes,
            String signedAttributesDigest,
            Instant signingTime,
            byte[] certificate,
            byte[] certificateChain,
            String certificateSerial,
            String certificateSha256
    ) {
    }
}
