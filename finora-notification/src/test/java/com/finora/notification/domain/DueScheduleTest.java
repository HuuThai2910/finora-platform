package com.finora.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D64: lịch nhắc chỉ chép kỳ Fineract báo qua event, bỏ event cũ đến muộn, và biết kỳ đã trả trước (D16). */
class DueScheduleTest {

    private static final Instant T0 = Instant.parse("2026-10-17T03:00:00Z");
    private static final LocalDate NOV_17 = LocalDate.parse("2026-11-17");
    private static final LocalDate DEC_17 = LocalDate.parse("2026-12-17");

    @Test
    void olderEventArrivingLateDoesNotMoveTheScheduleBack() {
        DueSchedule schedule = schedule();
        schedule.applyNextDue(DEC_17, new BigDecimal("1726000.00"), UUID.randomUUID(), T0.plusSeconds(60), T0);

        boolean applied = schedule.applyNextDue(NOV_17, new BigDecimal("1726000.00"), UUID.randomUUID(), T0, T0);

        assertThat(applied).isFalse();
        assertThat(schedule.getNextDueDate()).isEqualTo(DEC_17);
    }

    @Test
    void heldInstallmentSuppressesOnlyItsOwnDueDateAndClearsOnceTheScheduleMovesOn() {
        DueSchedule schedule = schedule();
        UUID repaymentId = UUID.randomUUID();
        schedule.markHeld(NOV_17, repaymentId, T0, T0);
        assertThat(schedule.isPaidEarlyFor(NOV_17)).isTrue();
        assertThat(schedule.isPaidEarlyFor(DEC_17)).isFalse();

        // Ngày đến hạn: Payment ghi khoản giữ vào Fineract, RepaymentDistributed báo kỳ tiếp theo.
        schedule.applyNextDue(DEC_17, new BigDecimal("1726000.00"), UUID.randomUUID(), T0.plusSeconds(3600), T0);

        assertThat(schedule.getHeldDueDate()).isNull();
        assertThat(schedule.isPaidEarlyFor(DEC_17)).isFalse();
    }

    @Test
    void cancellingTheHeldPaymentRearmsTheReminderButOnlyForThatPayment() {
        DueSchedule schedule = schedule();
        UUID repaymentId = UUID.randomUUID();
        schedule.markHeld(NOV_17, repaymentId, T0, T0);

        assertThat(schedule.releaseHeld(UUID.randomUUID(), T0)).isFalse();
        assertThat(schedule.releaseHeld(repaymentId, T0)).isTrue();
        assertThat(schedule.isPaidEarlyFor(NOV_17)).isFalse();
    }

    @Test
    void installmentAdjustmentMovesTheWaitingDueDateAndDropsTheStaleAmount() {
        DueSchedule schedule = schedule();

        schedule.adjustForReschedule(NOV_17, LocalDate.parse("2026-11-30"), UUID.randomUUID(), T0.plusSeconds(1), T0);

        assertThat(schedule.getNextDueDate()).isEqualTo(LocalDate.parse("2026-11-30"));
        assertThat(schedule.getNextDueAmount()).isNull();
    }

    @Test
    void noNextDueDateClosesTheSchedule() {
        DueSchedule schedule = schedule();

        schedule.applyNextDue(null, null, UUID.randomUUID(), T0.plusSeconds(1), T0);

        assertThat(schedule.getStatus()).isEqualTo(DueScheduleStatus.CLOSED);
    }

    private static DueSchedule schedule() {
        DueSchedule schedule = DueSchedule.create(12L, "42", "VND", UUID.randomUUID(), T0, T0);
        schedule.applyNextDue(NOV_17, new BigDecimal("1726000.00"), UUID.randomUUID(), T0, T0);
        return schedule;
    }
}
