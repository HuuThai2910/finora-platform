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
import com.finora.notification.service.template.NotificationText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.finora.notification.service.handler.impl.HandlerChecks.loanId;
import static com.finora.notification.service.handler.impl.HandlerChecks.loanReference;
import static com.finora.notification.service.handler.impl.HandlerChecks.required;

/**
 * {@code LoanDelinquencyChanged.v1} cho người vay (D61). Nhà đầu tư nhận tin rủi ro qua Investment
 * ({@code InvestorNoteServicingChanged}), không xử lý ở đây để không báo trùng.
 */
@Component
@RequiredArgsConstructor
public class DelinquencyEventsHandler implements InboundEventHandler {

    static final EventKey DELINQUENCY_CHANGED = new EventKey("LoanDelinquencyChanged", 1);

    private final EventPayloads eventPayloads;
    private final LoanRecipientService loanRecipientService;
    private final NotificationText notificationText;

    @Override
    public Set<EventKey> keys() {
        return Set.of(DELINQUENCY_CHANGED);
    }

    @Override
    public HandlerOutcome handle(InboundEvent event) {
        var payload = eventPayloads.read(event, LoanServicingEvents.DelinquencyChanged.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        String borrowerId = required(payload.borrowerId(), "borrowerId", event.eventType());
        if (payload.daysPastDue() < 0 || payload.debtGroup() < 1 || payload.debtGroup() > 5) {
            throw new IllegalArgumentException("LoanDelinquencyChanged có DPD/nhóm nợ ngoài miền hợp lệ");
        }
        // Mọi event đều cập nhật bảng tra (event này mang borrowerId và mã khoản vay) dù không sinh thông báo.
        LoanReference reference = loanRecipientService.remember(loanId, borrowerId, null, null, payload.loanNumber(),
                event.eventId());
        var template = DelinquencyNotificationPolicy.decide(payload.previousDaysPastDue(), payload.daysPastDue(),
                payload.previousDebtGroup(), payload.debtGroup());
        if (template.isEmpty()) return HandlerOutcome.filtered("SAME_DELINQUENCY_STAGE");
        Map<String, String> values = Map.of(
                "loan", notificationText.loanLabel(loanId, reference, payload.loanNumber(), null, null),
                "dpd", Integer.toString(payload.daysPastDue()),
                "overdue", notificationText.moneyOr(payload.overdueAmount(), "0.00"),
                "group", Integer.toString(payload.debtGroup()),
                "stage", DelinquencyNotificationPolicy.stageLabel(payload.debtGroup()));
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(reference.getBorrowerId(), template.get(), values,
                loanReference(loanId))));
    }
}
