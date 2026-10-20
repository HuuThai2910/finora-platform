package com.finora.notification.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Kỳ đến hạn tiếp theo của một khoản vay, Notification tự giữ để nhắc lịch (D64). Đây là projection hiển thị, không phải
 * lịch chính thức: Fineract là nguồn lịch, Loan/Payment chuyển số liệu qua event; Notification không tự tính kỳ hay tiền.
 * <p>
 * {@code heldDueDate}: người vay đã trả trước kỳ này và Payment đang giữ tiền tới ngày đến hạn (D16). Fineract vẫn báo kỳ
 * chưa trả cho tới ngày đó, nên chỉ cờ này giúp không nhắc nhầm người đã trả.
 * {@code sourceOccurredAt} chặn event cũ đến muộn ghi đè kỳ mới hơn.
 */
@Entity
@Table(name = "notification_due_schedules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DueSchedule {

    @Id @Column(name = "loan_application_id") private Long loanApplicationId;
    @Column(name = "borrower_id", nullable = false, length = 100) private String borrowerId;
    @Column(name = "next_due_date") private LocalDate nextDueDate;
    @Column(name = "next_due_amount", precision = 18, scale = 2) private BigDecimal nextDueAmount;
    @Column(nullable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) private DueScheduleStatus status;
    @Column(name = "source_event_id", nullable = false) private UUID sourceEventId;
    @Column(name = "source_occurred_at", nullable = false) private Instant sourceOccurredAt;
    @Column(name = "held_due_date") private LocalDate heldDueDate;
    @Column(name = "held_repayment_id") private UUID heldRepaymentId;
    @Column(name = "held_at") private Instant heldAt;
    /** Optimistic lock: RepaymentDistributed và RepaymentCollected (hai listener) có thể sửa cùng dòng cùng lúc. */
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static DueSchedule create(Long loanApplicationId, String borrowerId, String currency, UUID sourceEventId,
            Instant sourceOccurredAt, Instant now) {
        DueSchedule value = new DueSchedule();
        value.loanApplicationId = loanApplicationId;
        value.borrowerId = borrowerId;
        value.currency = currency == null || currency.isBlank() ? "VND" : currency;
        value.status = DueScheduleStatus.ACTIVE;
        value.sourceEventId = sourceEventId;
        value.sourceOccurredAt = sourceOccurredAt;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    /**
     * Ghi kỳ tiếp theo từ một event; trả {@code false} và giữ nguyên khi event cũ hơn dữ liệu đang có.
     * {@code nextDueDate == null} nghĩa là không còn kỳ nào (đã trả hết) nên lịch đóng lại.
     */
    public boolean applyNextDue(LocalDate nextDueDate, BigDecimal nextDueAmount, UUID sourceEventId,
            Instant occurredAt, Instant now) {
        if (occurredAt.isBefore(sourceOccurredAt)) return false;
        this.nextDueDate = nextDueDate;
        this.nextDueAmount = nextDueAmount;
        this.status = nextDueDate == null ? DueScheduleStatus.CLOSED : DueScheduleStatus.ACTIVE;
        this.sourceEventId = sourceEventId;
        this.sourceOccurredAt = occurredAt;
        // Kỳ đang giữ đã qua (đã ghi vào sổ nợ) thì cờ giữ không còn ý nghĩa với kỳ mới.
        if (heldDueDate != null && (nextDueDate == null || heldDueDate.isBefore(nextDueDate))) clearHeld();
        this.updatedAt = now;
        return true;
    }

    /**
     * Cơ cấu đã được Fineract chấp nhận nhưng event chưa mang kỳ mới (P3). Điều chỉnh kỳ: Fineract dời đúng kỳ có ngày
     * {@code rescheduleFromDate} sang {@code adjustedDueDate}, nên chỉ dời khi đó đúng là kỳ đang chờ. Mọi kiểu cơ cấu
     * đều có thể đổi số tiền kỳ, nên bỏ số tiền cũ để lời nhắc không ghi sai số.
     */
    public boolean adjustForReschedule(LocalDate rescheduleFromDate, LocalDate adjustedDueDate, UUID sourceEventId,
            Instant occurredAt, Instant now) {
        if (occurredAt.isBefore(sourceOccurredAt)) return false;
        if (adjustedDueDate != null && nextDueDate != null && nextDueDate.equals(rescheduleFromDate)) {
            nextDueDate = adjustedDueDate;
            if (heldDueDate != null && heldDueDate.equals(rescheduleFromDate)) clearHeld();
        }
        nextDueAmount = null;
        this.sourceEventId = sourceEventId;
        this.sourceOccurredAt = occurredAt;
        updatedAt = now;
        return true;
    }

    public void close(UUID sourceEventId, Instant occurredAt, Instant now) {
        status = DueScheduleStatus.CLOSED;
        nextDueDate = null;
        nextDueAmount = null;
        clearHeld();
        this.sourceEventId = sourceEventId;
        if (occurredAt.isAfter(sourceOccurredAt)) sourceOccurredAt = occurredAt;
        updatedAt = now;
    }

    public void markHeld(LocalDate dueDate, UUID repaymentId, Instant heldAt, Instant now) {
        this.heldDueDate = dueDate;
        this.heldRepaymentId = repaymentId;
        this.heldAt = heldAt;
        this.updatedAt = now;
    }

    /** Người vay hủy khoản giữ (tiền về ví): kỳ đó lại cần được nhắc. Chỉ gỡ đúng khoản đang giữ. */
    public boolean releaseHeld(UUID repaymentId, Instant now) {
        if (heldRepaymentId == null || !heldRepaymentId.equals(repaymentId)) return false;
        clearHeld();
        updatedAt = now;
        return true;
    }

    public boolean isPaidEarlyFor(LocalDate dueDate) {
        return heldDueDate != null && heldDueDate.equals(dueDate);
    }

    private void clearHeld() {
        heldDueDate = null;
        heldRepaymentId = null;
        heldAt = null;
    }
}
