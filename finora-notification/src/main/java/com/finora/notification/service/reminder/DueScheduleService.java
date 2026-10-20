package com.finora.notification.service.reminder;

import com.finora.notification.domain.DueSchedule;
import com.finora.notification.repository.DueScheduleRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cập nhật lịch nhắc từ event (D64), trong transaction của intake. Không tự tính kỳ: chỉ chép {@code nextDueDate/
 * nextDueAmount} mà Payment/Loan đọc từ Fineract, và cờ khoản trả trước đang giữ của Payment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DueScheduleService {

    private final DueScheduleRepository schedules;
    private final Clock clock;

    /** Kỳ tiếp theo sau một lần trả nợ/giải ngân/cơ cấu; {@code nextDueDate == null} là hết kỳ. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyNextDue(Long loanApplicationId, String borrowerId, String currency, LocalDate nextDueDate,
            BigDecimal nextDueAmount, UUID sourceEventId, Instant occurredAt) {
        Instant now = clock.instant();
        DueSchedule schedule = schedules.findForUpdate(loanApplicationId).orElse(null);
        if (schedule == null) {
            if (nextDueDate == null) return;
            schedule = DueSchedule.create(loanApplicationId, borrowerId, currency, sourceEventId, occurredAt, now);
            schedule.applyNextDue(nextDueDate, nextDueAmount, sourceEventId, occurredAt, now);
            schedules.save(schedule);
            return;
        }
        if (!schedule.applyNextDue(nextDueDate, nextDueAmount, sourceEventId, occurredAt, now)) {
            log.debug("Bỏ qua kỳ đến hạn cũ hơn dữ liệu đang có: loanApplicationId={}, eventId={}",
                    loanApplicationId, sourceEventId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reschedule(Long loanApplicationId, LocalDate rescheduleFromDate, LocalDate adjustedDueDate,
            UUID sourceEventId, Instant occurredAt) {
        schedules.findForUpdate(loanApplicationId).ifPresent(schedule -> schedule.adjustForReschedule(
                rescheduleFromDate, adjustedDueDate, sourceEventId, occurredAt, clock.instant()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void close(Long loanApplicationId, UUID sourceEventId, Instant occurredAt) {
        schedules.findForUpdate(loanApplicationId)
                .ifPresent(schedule -> schedule.close(sourceEventId, occurredAt, clock.instant()));
    }

    /**
     * Payment giữ khoản trả kỳ tới ngày đến hạn (D16): không nhắc kỳ đó. Kỳ đầu tiên có thể được trả trước khi
     * Notification biết lịch (chưa có {@code RepaymentDistributed} nào), nên tạo lịch từ chính ngày đến hạn của khoản giữ.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markHeld(Long loanApplicationId, String borrowerId, String currency, LocalDate dueDate,
            UUID repaymentId, Instant heldAt, UUID sourceEventId) {
        Instant now = clock.instant();
        DueSchedule schedule = schedules.findForUpdate(loanApplicationId).orElse(null);
        if (schedule == null) {
            schedule = DueSchedule.create(loanApplicationId, borrowerId, currency, sourceEventId, heldAt, now);
            schedule.applyNextDue(dueDate, null, sourceEventId, heldAt, now);
            schedules.save(schedule);
        }
        schedule.markHeld(dueDate, repaymentId, heldAt, now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseHeld(Long loanApplicationId, UUID repaymentId) {
        schedules.findForUpdate(loanApplicationId)
                .ifPresent(schedule -> schedule.releaseHeld(repaymentId, clock.instant()));
    }
}
