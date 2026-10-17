package com.finora.notification.service.handler;

import static com.finora.notification.service.handler.HandlerChecks.loanId;
import static com.finora.notification.service.handler.HandlerChecks.loanReference;
import static com.finora.notification.service.handler.HandlerChecks.required;

import com.finora.notification.domain.LoanReference;
import com.finora.notification.messaging.event.LoanServicingEvents;
import com.finora.notification.service.delivery.NotificationDraft;
import com.finora.notification.service.intake.EventKey;
import com.finora.notification.service.intake.EventPayloads;
import com.finora.notification.service.intake.HandlerOutcome;
import com.finora.notification.service.intake.InboundEvent;
import com.finora.notification.service.intake.InboundEventHandler;
import com.finora.notification.service.recipient.LoanRecipientService;
import com.finora.notification.service.reminder.DueScheduleService;
import com.finora.notification.service.template.NotificationTemplate;
import com.finora.notification.service.template.NotificationText;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Cơ cấu thời hạn trả nợ cho người vay (F07): FINORA thẩm định đạt, kết quả biểu quyết của nhà đầu tư, lịch mới có hiệu
 * lực. Admin từ chối yêu cầu hiện không phát event (đề xuất P8), nên chưa báo được. Nhà đầu tư nhận tin cơ cấu xong qua
 * Investment ({@code RESCHEDULED}); tin mời biểu quyết chờ Investment phát {@code RESCHEDULE_CONSENT_REQUESTED} (P7).
 */
@Component
@RequiredArgsConstructor
public class RestructuringEventsHandler implements InboundEventHandler {

    static final EventKey CONSENT_REQUESTED = new EventKey("LoanRescheduleConsentRequested", 1);
    static final EventKey CONSENT_COMPLETED = new EventKey("LoanRescheduleConsentCompleted", 1);
    static final EventKey RESCHEDULED = new EventKey("LoanRescheduled", 1);

    private final EventPayloads payloads;
    private final LoanRecipientService recipients;
    private final DueScheduleService dueSchedules;
    private final NotificationText text;

    @Override
    public Set<EventKey> keys() {
        return Set.of(CONSENT_REQUESTED, CONSENT_COMPLETED, RESCHEDULED);
    }

    @Override
    public HandlerOutcome handle(InboundEvent event) {
        return switch (event.eventType()) {
            case "LoanRescheduleConsentRequested" -> consentRequested(event);
            case "LoanRescheduleConsentCompleted" -> consentCompleted(event);
            case "LoanRescheduled" -> rescheduled(event);
            default -> throw new IllegalArgumentException("RestructuringEventsHandler không nhận " + event.eventType());
        };
    }

    private HandlerOutcome consentRequested(InboundEvent event) {
        var data = payloads.read(event, LoanServicingEvents.RescheduleConsentRequested.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        required(data.requestId(), "requestId", event.eventType());
        Optional<String> borrower = recipients.borrower(loanId, data.borrowerId(), null, null, data.loanNumber(),
                event.eventId());
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", List.of());
        LoanReference reference = recipients.find(loanId).orElse(null);
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrower.get(),
                NotificationTemplate.RESCHEDULE_UNDER_INVESTOR_VOTE,
                Map.of("loan", text.loanLabel(loanId, reference, data.loanNumber(), null, null)),
                loanReference(loanId))));
    }

    private HandlerOutcome consentCompleted(InboundEvent event) {
        var data = payloads.read(event, LoanServicingEvents.RescheduleConsentCompleted.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        required(data.requestId(), "requestId", event.eventType());
        Optional<String> borrower = recipients.borrower(loanId, null, null, null, null, event.eventId());
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", List.of());
        LoanReference reference = recipients.find(loanId).orElse(null);
        String loan = text.loanLabel(loanId, reference, null, null, null);
        NotificationDraft draft = data.approved()
                ? NotificationDraft.of(borrower.get(), NotificationTemplate.RESCHEDULE_APPROVED_BY_INVESTORS,
                        Map.of("loan", loan, "approvedInvestors", Integer.toString(data.approvedInvestors())),
                        loanReference(loanId))
                : NotificationDraft.of(borrower.get(), NotificationTemplate.RESCHEDULE_REJECTED_BY_INVESTORS,
                        Map.of("loan", loan), loanReference(loanId));
        return HandlerOutcome.deliver(List.of(draft));
    }

    private HandlerOutcome rescheduled(InboundEvent event) {
        var data = payloads.read(event, LoanServicingEvents.Rescheduled.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        String borrowerId = required(data.borrowerId(), "borrowerId", event.eventType());
        LoanReference reference = recipients.remember(loanId, borrowerId, null, null, data.loanNumber(),
                event.eventId());
        Instant occurredAt = data.occurredAt() == null ? event.occurredAt() : data.occurredAt();
        if (data.nextDueDate() != null) {
            dueSchedules.applyNextDue(loanId, borrowerId, null, data.nextDueDate(),
                    text.decimal(data.nextDueAmount()), event.eventId(), occurredAt);
        } else {
            dueSchedules.reschedule(loanId, data.rescheduleFromDate(), data.adjustedDueDate(), event.eventId(),
                    occurredAt);
        }
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrowerId, NotificationTemplate.LOAN_RESCHEDULED,
                Map.of("loan", text.loanLabel(loanId, reference, data.loanNumber(), null, null),
                        "maturityDate", text.date(data.newMaturityDate())),
                loanReference(loanId))));
    }
}
