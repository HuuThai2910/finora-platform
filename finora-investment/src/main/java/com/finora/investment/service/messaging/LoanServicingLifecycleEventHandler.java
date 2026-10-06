package com.finora.investment.service.messaging;

import com.finora.investment.domain.messaging.ProcessedEvent;
import com.finora.investment.domain.note.InvestmentLoanServicingState;
import com.finora.investment.messaging.event.LoanRescheduledEventData;
import com.finora.investment.messaging.event.LoanSettledEventData;
import com.finora.investment.messaging.event.LoanDelinquencyChangedEventData;
import com.finora.investment.messaging.event.InvestorServicingNotificationEventData;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import com.finora.common.enums.investment.NoteStatus;
import java.math.BigDecimal;
import com.finora.investment.repository.InvestmentLoanServicingStateRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoanServicingLifecycleEventHandler {
    private final InvestmentLoanServicingStateRepository states;
    private final ProcessedEventRepository processed;
    private final InvestmentNoteRepository notes;
    private final InvestmentOutboxService outbox;
    private final Clock clock;

    @Transactional
    public void rescheduled(UUID eventId, Instant receivedAt, LoanRescheduledEventData data) {
        if (processed.existsByEventId(eventId)) return;
        var now = clock.instant();
        var state = states.findByApplicationIdForUpdate(data.loanApplicationId()).orElse(null);
        if (state == null) {
            states.save(InvestmentLoanServicingState.rescheduled(data.loanApplicationId(), data.loanNumber(),
                    data.requestId(), data.newMaturityDate(), data.occurredAt(), now));
        } else {
            state.applyReschedule(data.requestId(), data.newMaturityDate(), data.occurredAt(), now);
        }
        publishToInvestors(data.loanApplicationId(), data.loanNumber(), "RESCHEDULED",
                0, 1, "0.00", data.newMaturityDate(), true, data.occurredAt());
        processed.save(ProcessedEvent.create(eventId, "LoanRescheduled", 1, "finora-loan",
                data.requestId().toString(), receivedAt, now));
    }

    @Transactional
    public void settled(UUID eventId, Instant receivedAt, LoanSettledEventData data) {
        if (processed.existsByEventId(eventId)) return;
        var now = clock.instant();
        var state = states.findByApplicationIdForUpdate(data.loanApplicationId()).orElse(null);
        if (state == null) {
            states.save(InvestmentLoanServicingState.settled(data.loanApplicationId(), data.loanNumber(),
                    data.settledAt(), now));
        } else {
            state.settle(data.settledAt(), now);
        }
        publishToInvestors(data.loanApplicationId(), data.loanNumber(), "SETTLED",
                0, 1, "0.00", null, true, data.settledAt());
        processed.save(ProcessedEvent.create(eventId, "LoanSettled", 1, "finora-loan",
                data.loanApplicationId().toString(), receivedAt, now));
    }

    @Transactional
    public void delinquencyChanged(UUID eventId, Instant receivedAt,
            LoanDelinquencyChangedEventData data) {
        if (processed.existsByEventId(eventId)) return;
        validate(data);
        var now = clock.instant();
        var state = states.findByApplicationIdForUpdate(data.loanApplicationId()).orElse(null);
        BigDecimal overdue = new BigDecimal(data.overdueAmount());
        BigDecimal outstanding = new BigDecimal(data.totalOutstanding());
        if (state == null) {
            states.save(InvestmentLoanServicingState.delinquency(
                    data.loanApplicationId(), data.loanNumber(), data.daysPastDue(),
                    data.debtGroup(), overdue, data.overdueSince(), outstanding,
                    data.dataAsOf(), receivedAt, now));
        } else {
            state.applyDelinquency(data.daysPastDue(), data.debtGroup(), overdue,
                    data.overdueSince(), outstanding, data.dataAsOf(), receivedAt, now);
        }
        String changeType = data.daysPastDue() == 0 && data.previousDaysPastDue() > 0
                ? "DELINQUENCY_CURED"
                : data.debtGroup() >= 3 ? "BAD_DEBT_MILESTONE" : "DELINQUENCY_CHANGED";
        boolean push = data.daysPastDue() == 0
                || (data.debtGroup() != data.previousDebtGroup() && data.debtGroup() >= 2);
        publishToInvestors(data.loanApplicationId(), data.loanNumber(), changeType,
                data.daysPastDue(), data.debtGroup(), data.overdueAmount(), null,
                push, data.dataAsOf());
        processed.save(ProcessedEvent.create(eventId, "LoanDelinquencyChanged", 1,
                "finora-loan", data.loanApplicationId().toString(), receivedAt, now));
    }

    private void validate(LoanDelinquencyChangedEventData data) {
        if (data == null || data.loanApplicationId() == null || data.loanNumber() == null
                || data.loanNumber().isBlank() || data.daysPastDue() < 0
                || data.debtGroup() < 1 || data.debtGroup() > 5 || data.dataAsOf() == null
                || new BigDecimal(data.overdueAmount()).signum() < 0
                || new BigDecimal(data.totalOutstanding()).signum() < 0) {
            throw new IllegalArgumentException("LoanDelinquencyChanged data không hợp lệ");
        }
    }

    private void publishToInvestors(Long loanId, String loanNumber, String changeType,
            int daysPastDue, int debtGroup, String amount, java.time.LocalDate maturityDate,
            boolean push, Instant changedAt) {
        var recipients = notes.findByLoanIdAndStatusIn(loanId,
                        java.util.List.of(NoteStatus.ACTIVE, NoteStatus.DEFAULTED)).stream()
                .map(note -> new InvestorServicingNotificationEventData.Recipient(
                        note.getId(), note.getNoteNumber(), note.getInvestorId()))
                .toList();
        if (recipients.isEmpty()) return;
        outbox.record("LOAN", loanId.toString(), "InvestorNoteServicingChanged", 1,
                new InvestorServicingNotificationEventData(changeType, loanId, loanNumber,
                        daysPastDue, debtGroup, amount, maturityDate, push, changedAt, recipients));
    }
}
