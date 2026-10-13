package com.finora.investment.domain.secondary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Khoản trả nợ bị Payment chia cho chủ cũ của Note, cần chuyển lại cho chủ hiện tại.
 *
 * <p>Khớp lệnh đổi chủ Note ngay trong DB Investment, còn Payment biết chủ mới qua event
 * {@code NoteOwnershipChanged} (bất đồng bộ). Repayment được phân phối trong khoảng giữa hai thời
 * điểm đó trả tiền cho người bán — trong khi người mua đã trả giá tính trên dư nợ trước kỳ trả này.
 * Bản ghi này là khoản phải thu từ người bán, worker chuyển sang người mua tới khi xong (giống
 * "due bill" trên thị trường chứng khoán).</p>
 */
@Entity
@Table(name = "note_repayment_redirects")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoteRepaymentRedirect {

    public static final String PENDING = "PENDING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Khóa idempotency phía Payment: gọi lại cùng mã chỉ chuyển một lần. */
    @Column(name = "redirect_reference", nullable = false, length = 100, unique = true, updatable = false)
    private String redirectReference;

    @Column(name = "repayment_id", nullable = false, updatable = false)
    private UUID repaymentId;

    @Column(name = "note_id", nullable = false, updatable = false)
    private Long noteId;

    @Column(name = "from_investor_id", nullable = false, length = 100, updatable = false)
    private String fromInvestorId;

    @Column(name = "to_investor_id", nullable = false, length = 100, updatable = false)
    private String toInvestorId;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error_code", length = 50)
    private String lastErrorCode;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static NoteRepaymentRedirect pending(UUID repaymentId, Long noteId, String fromInvestorId,
            String toInvestorId, BigDecimal amount, Instant now) {
        NoteRepaymentRedirect redirect = new NoteRepaymentRedirect();
        redirect.redirectReference = "REPAY-REDIRECT:" + repaymentId + ":" + noteId;
        redirect.repaymentId = repaymentId;
        redirect.noteId = noteId;
        redirect.fromInvestorId = fromInvestorId;
        redirect.toInvestorId = toInvestorId;
        redirect.amount = amount;
        redirect.status = PENDING;
        redirect.attempts = 0;
        redirect.nextAttemptAt = now;
        redirect.createdAt = now;
        redirect.updatedAt = now;
        return redirect;
    }

    public void complete(String paymentReference, Instant now) {
        this.status = COMPLETED;
        this.paymentReference = paymentReference;
        this.completedAt = now;
        this.updatedAt = now;
    }

    /** Lỗi tạm thời hoặc người bán chưa đủ số dư: thử lại sau, khoản phải thu vẫn còn. */
    public void retryLater(String errorCode, Duration backoff, Instant now) {
        this.attempts++;
        this.lastErrorCode = errorCode;
        this.nextAttemptAt = now.plus(backoff);
        this.updatedAt = now;
    }

    /** Payment từ chối vì lý do không tự hết: dừng để đối soát tay. */
    public void fail(String errorCode, Instant now) {
        this.attempts++;
        this.status = FAILED;
        this.lastErrorCode = errorCode;
        this.updatedAt = now;
    }
}
