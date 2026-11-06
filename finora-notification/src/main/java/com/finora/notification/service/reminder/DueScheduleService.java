package com.finora.notification.service.reminder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cập nhật lịch nhắc từ event (D64), trong transaction của intake. Không tự tính kỳ: chỉ chép {@code nextDueDate/
 * nextDueAmount} mà Payment/Loan đọc từ Fineract, và cờ khoản trả trước đang giữ của Payment.
 */
public interface DueScheduleService {

    /** Kỳ tiếp theo sau một lần trả nợ/giải ngân/cơ cấu; {@code nextDueDate == null} là hết kỳ. */
    void applyNextDue(Long loanApplicationId, String borrowerId, String currency, LocalDate nextDueDate,
            BigDecimal nextDueAmount, UUID sourceEventId, Instant occurredAt);

    void reschedule(Long loanApplicationId, LocalDate rescheduleFromDate, LocalDate adjustedDueDate,
            UUID sourceEventId, Instant occurredAt);

    void close(Long loanApplicationId, UUID sourceEventId, Instant occurredAt);

    /**
     * Payment giữ khoản trả kỳ tới ngày đến hạn (D16): không nhắc kỳ đó. Kỳ đầu tiên có thể được trả trước khi
     * Notification biết lịch (chưa có {@code RepaymentDistributed} nào), nên tạo lịch từ chính ngày đến hạn của khoản giữ.
     */
    void markHeld(Long loanApplicationId, String borrowerId, String currency, LocalDate dueDate,
            UUID repaymentId, Instant heldAt, UUID sourceEventId);

    void releaseHeld(Long loanApplicationId, UUID repaymentId);
}
