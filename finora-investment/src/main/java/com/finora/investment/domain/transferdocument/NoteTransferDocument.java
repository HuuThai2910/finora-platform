package com.finora.investment.domain.transferdocument;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Giấy xác nhận chuyển nhượng Note của một lần khớp ({@code order_book_trades}).
 *
 * <p>Văn bản do FINORA phát hành sau khi thanh toán xong; hai bên không ký. PDF đã đóng dấu số được lưu nguyên bytes
 * nên tải lại luôn ra đúng tệp có hash đã neo. Sau khi {@code ISSUED} không còn đổi gì.</p>
 */
@Entity
@Table(name = "note_transfer_documents")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoteTransferDocument {

    /** Tiền tố số văn bản; số đầy đủ là {@code CN-<mã giao dịch>}. */
    public static final String NUMBER_PREFIX = "CN-";
    private static final int MAX_ERROR_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_id", nullable = false, unique = true, updatable = false)
    private Long tradeId;

    @Column(name = "trade_reference", nullable = false, length = 50, unique = true, updatable = false)
    private String tradeReference;

    @Column(name = "document_number", nullable = false, length = 60, unique = true, updatable = false)
    private String documentNumber;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private Long loanId;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private NoteTransferDocumentStatus status;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Lob
    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "content", columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "proof_payload", columnDefinition = "text")
    private String proofPayload;

    @Column(name = "seal_subject", length = 200)
    private String sealSubject;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static NoteTransferDocument pending(
            Long tradeId, String tradeReference, Long loanId, Long listingId, Instant now) {
        return NoteTransferDocument.builder()
                .tradeId(tradeId)
                .tradeReference(tradeReference)
                .documentNumber(NUMBER_PREFIX + tradeReference)
                .loanId(loanId)
                .listingId(listingId)
                .status(NoteTransferDocumentStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * Nhận lượt lập văn bản: đẩy lượt kế tiếp ra sau {@code lease} để lần quét khác không lập trùng trong lúc PDF đang
     * được dựng ngoài transaction. Tiến trình chết giữa chừng thì hết lease là lập lại.
     */
    public void claim(Duration lease, Instant now) {
        attempts = attempts + 1;
        nextAttemptAt = now.plus(lease);
        updatedAt = now;
    }

    /** Bản sao PDF đã đóng dấu; null khi chưa {@code ISSUED}. */
    public byte[] contentCopy() {
        return content == null ? null : Arrays.copyOf(content, content.length);
    }

    public boolean isIssued() {
        return status == NoteTransferDocumentStatus.ISSUED;
    }

    public void markIssued(byte[] pdf, String hash, String payload, String sealSubject, Instant now) {
        this.content = Arrays.copyOf(pdf, pdf.length);
        this.contentHash = hash;
        this.proofPayload = payload;
        this.sealSubject = truncate(sealSubject);
        this.status = NoteTransferDocumentStatus.ISSUED;
        this.issuedAt = now;
        this.nextAttemptAt = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    /** Lỗi tạm thời: hẹn lượt sau theo backoff, giữ {@code PENDING}. */
    public void scheduleRetry(String error, Duration backoff, Instant now) {
        lastError = truncate(error);
        nextAttemptAt = now.plus(backoff);
        updatedAt = now;
    }

    /** Đã thử hết số lần: dừng, chờ quản trị viên thử lại. */
    public void markFailed(String error, Instant now) {
        status = NoteTransferDocumentStatus.FAILED;
        lastError = truncate(error);
        nextAttemptAt = null;
        updatedAt = now;
    }

    /** Quản trị viên thử lại văn bản {@code FAILED}: đếm lại số lần từ đầu. */
    public void reopen(Instant now) {
        status = NoteTransferDocumentStatus.PENDING;
        attempts = 0;
        nextAttemptAt = now;
        updatedAt = now;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
