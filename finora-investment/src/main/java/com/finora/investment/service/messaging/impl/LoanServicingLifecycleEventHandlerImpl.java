package com.finora.investment.service.messaging.impl;

import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.domain.messaging.ProcessedEvent;
import com.finora.investment.domain.note.InvestmentLoanServicingState;
import com.finora.investment.messaging.event.InvestorServicingNotificationEventData;
import com.finora.investment.messaging.event.LoanDelinquencyChangedEventData;
import com.finora.investment.messaging.event.LoanRescheduledEventData;
import com.finora.investment.messaging.event.LoanSettledEventData;
import com.finora.investment.repository.InvestmentLoanServicingStateRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import com.finora.investment.service.messaging.LoanServicingLifecycleEventHandler;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanServicingLifecycleEventHandlerImpl implements LoanServicingLifecycleEventHandler {

    private static final int BAD_DEBT_GROUP = 3;

    private final InvestmentLoanServicingStateRepository states;
    private final ProcessedEventRepository processed;
    private final InvestmentNoteRepository notes;
    private final InvestmentOutboxService outbox;
    private final Clock clock;

    @Override
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

    @Override
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

    @Override
    @Transactional
    public void delinquencyChanged(UUID eventId, Instant receivedAt,
            LoanDelinquencyChangedEventData data) {
        if (processed.existsByEventId(eventId)) return;
        validate(data);
        var now = clock.instant();
        var state = states.findByApplicationIdForUpdate(data.loanApplicationId()).orElse(null);
        BigDecimal overdue = new BigDecimal(data.overdueAmount());
        BigDecimal outstanding = new BigDecimal(data.totalOutstanding());
        boolean applied = true;
        if (state == null) {
            states.save(InvestmentLoanServicingState.delinquency(
                    data.loanApplicationId(), data.loanNumber(), data.daysPastDue(),
                    data.debtGroup(), overdue, data.overdueSince(), outstanding,
                    data.dataAsOf(), receivedAt, now));
        } else {
            applied = state.applyDelinquency(data.daysPastDue(), data.debtGroup(), overdue,
                    data.overdueSince(), outstanding, data.dataAsOf(), receivedAt, now);
        }
        if (applied) {
            syncNoteDefaultStatus(data.loanApplicationId(), data.debtGroup(), now);
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

    /**
     * Nhóm nợ 3–5 là nợ xấu (Thông tư 11/2021/TT-NHNN, Điều 10): Note của khoản vay chuyển
     * DEFAULTED để sổ lệnh bắt người mua xác nhận rủi ro và ghi cờ nợ xấu vào giao dịch. Nhóm nợ
     * về lại 1–2 thì trả Note về ACTIVE. Event cũ đến muộn đã bị lọc ở {@code applyDelinquency}.
     */
    private void syncNoteDefaultStatus(Long loanId, int debtGroup, Instant now) {
        int changed = debtGroup >= BAD_DEBT_GROUP
                ? notes.transitionStatusByLoan(loanId, NoteStatus.ACTIVE, NoteStatus.DEFAULTED, now)
                : notes.transitionStatusByLoan(loanId, NoteStatus.DEFAULTED, NoteStatus.ACTIVE, now);
        if (changed > 0) {
            log.info("Đổi trạng thái nợ xấu của Note: loanId={}, debtGroup={}, notes={}",
                    loanId, debtGroup, changed);
        }
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
                        note.getId(), note.getNoteNumber(), note.getInvestorId(), null))
                .toList();
        if (recipients.isEmpty()) return;
        outbox.record("LOAN", loanId.toString(), "InvestorNoteServicingChanged", 1,
                new InvestorServicingNotificationEventData(changeType, loanId, loanNumber,
                        daysPastDue, debtGroup, amount, maturityDate, push, changedAt, recipients));
    }
}
