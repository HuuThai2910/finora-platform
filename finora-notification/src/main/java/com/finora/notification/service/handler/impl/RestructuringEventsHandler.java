package com.finora.notification.service.handler.impl;

import com.finora.notification.domain.entity.LoanReference;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.finora.notification.service.handler.impl.HandlerChecks.loanId;
import static com.finora.notification.service.handler.impl.HandlerChecks.loanReference;
import static com.finora.notification.service.handler.impl.HandlerChecks.required;

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

    private final EventPayloads eventPayloads;
    private final LoanRecipientService loanRecipientService;
    private final DueScheduleService dueScheduleService;
    private final NotificationText notificationText;

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
        var payload = eventPayloads.read(event, LoanServicingEvents.RescheduleConsentRequested.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        required(payload.requestId(), "requestId", event.eventType());
        Optional<String> borrower = loanRecipientService.borrower(loanId, payload.borrowerId(), null, null,
                payload.loanNumber(), event.eventId());
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", List.of());
        LoanReference reference = loanRecipientService.find(loanId).orElse(null);
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrower.get(),
                NotificationTemplate.RESCHEDULE_UNDER_INVESTOR_VOTE,
                Map.of("loan", notificationText.loanLabel(loanId, reference, payload.loanNumber(), null, null)),
                loanReference(loanId))));
    }

    private HandlerOutcome consentCompleted(InboundEvent event) {
        var payload = eventPayloads.read(event, LoanServicingEvents.RescheduleConsentCompleted.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        required(payload.requestId(), "requestId", event.eventType());
        Optional<String> borrower = loanRecipientService.borrower(loanId, null, null, null, null, event.eventId());
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", List.of());
        LoanReference reference = loanRecipientService.find(loanId).orElse(null);
        String loan = notificationText.loanLabel(loanId, reference, null, null, null);
        NotificationDraft draft = payload.approved()
                ? NotificationDraft.of(borrower.get(), NotificationTemplate.RESCHEDULE_APPROVED_BY_INVESTORS,
                        Map.of("loan", loan, "approvedInvestors", Integer.toString(payload.approvedInvestors())),
                        loanReference(loanId))
                : NotificationDraft.of(borrower.get(), NotificationTemplate.RESCHEDULE_REJECTED_BY_INVESTORS,
                        Map.of("loan", loan), loanReference(loanId));
        return HandlerOutcome.deliver(List.of(draft));
    }

    private HandlerOutcome rescheduled(InboundEvent event) {
        var payload = eventPayloads.read(event, LoanServicingEvents.Rescheduled.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        String borrowerId = required(payload.borrowerId(), "borrowerId", event.eventType());
        LoanReference reference = loanRecipientService.remember(loanId, borrowerId, null, null, payload.loanNumber(),
                event.eventId());
        Instant occurredAt = payload.occurredAt() == null ? event.occurredAt() : payload.occurredAt();
        if (payload.nextDueDate() != null) {
            dueScheduleService.applyNextDue(loanId, borrowerId, null, payload.nextDueDate(),
                    notificationText.decimal(payload.nextDueAmount()), event.eventId(), occurredAt);
        } else {
            dueScheduleService.reschedule(loanId, payload.rescheduleFromDate(), payload.adjustedDueDate(),
                    event.eventId(), occurredAt);
        }
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrowerId, NotificationTemplate.LOAN_RESCHEDULED,
                Map.of("loan", notificationText.loanLabel(loanId, reference, payload.loanNumber(), null, null),
                        "maturityDate", notificationText.date(payload.newMaturityDate())),
                loanReference(loanId))));
    }
}
