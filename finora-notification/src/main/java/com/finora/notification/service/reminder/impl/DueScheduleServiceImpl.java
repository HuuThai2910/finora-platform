package com.finora.notification.service.reminder.impl;

import com.finora.notification.domain.entity.DueSchedule;
import com.finora.notification.repository.DueScheduleRepository;
import com.finora.notification.service.reminder.DueScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cập nhật lịch nhắc từ event (D64), trong transaction của intake. Không tự tính kỳ: chỉ chép {@code nextDueDate/
 * nextDueAmount} mà Payment/Loan đọc từ Fineract, và cờ khoản trả trước đang giữ của Payment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DueScheduleServiceImpl implements DueScheduleService {

    private final DueScheduleRepository dueScheduleRepository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyNextDue(Long loanApplicationId, String borrowerId, String currency, LocalDate nextDueDate,
            BigDecimal nextDueAmount, UUID sourceEventId, Instant occurredAt) {
        Instant now = clock.instant();
        DueSchedule schedule = dueScheduleRepository.findForUpdate(loanApplicationId).orElse(null);
        if (schedule == null) {
            if (nextDueDate == null) return;
            schedule = DueSchedule.create(loanApplicationId, borrowerId, currency, sourceEventId, occurredAt, now);
            schedule.applyNextDue(nextDueDate, nextDueAmount, sourceEventId, occurredAt, now);
            dueScheduleRepository.save(schedule);
            return;
        }
        if (!schedule.applyNextDue(nextDueDate, nextDueAmount, sourceEventId, occurredAt, now)) {
            log.debug("Bỏ qua kỳ đến hạn cũ hơn dữ liệu đang có: loanApplicationId={}, eventId={}",
                    loanApplicationId, sourceEventId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reschedule(Long loanApplicationId, LocalDate rescheduleFromDate, LocalDate adjustedDueDate,
            UUID sourceEventId, Instant occurredAt) {
        dueScheduleRepository.findForUpdate(loanApplicationId).ifPresent(schedule -> schedule.adjustForReschedule(
                rescheduleFromDate, adjustedDueDate, sourceEventId, occurredAt, clock.instant()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void close(Long loanApplicationId, UUID sourceEventId, Instant occurredAt) {
        dueScheduleRepository.findForUpdate(loanApplicationId)
                .ifPresent(schedule -> schedule.close(sourceEventId, occurredAt, clock.instant()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markHeld(Long loanApplicationId, String borrowerId, String currency, LocalDate dueDate,
            UUID repaymentId, Instant heldAt, UUID sourceEventId) {
        Instant now = clock.instant();
        DueSchedule schedule = dueScheduleRepository.findForUpdate(loanApplicationId).orElse(null);
        if (schedule == null) {
            schedule = DueSchedule.create(loanApplicationId, borrowerId, currency, sourceEventId, heldAt, now);
            schedule.applyNextDue(dueDate, null, sourceEventId, heldAt, now);
            dueScheduleRepository.save(schedule);
        }
        schedule.markHeld(dueDate, repaymentId, heldAt, now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseHeld(Long loanApplicationId, UUID repaymentId) {
        dueScheduleRepository.findForUpdate(loanApplicationId)
                .ifPresent(schedule -> schedule.releaseHeld(repaymentId, clock.instant()));
    }
}
