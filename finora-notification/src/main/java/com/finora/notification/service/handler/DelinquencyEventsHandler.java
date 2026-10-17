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
import com.finora.notification.service.template.NotificationText;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code LoanDelinquencyChanged.v1} cho người vay (D61). Nhà đầu tư nhận tin rủi ro qua Investment
 * ({@code InvestorNoteServicingChanged}), không xử lý ở đây để không báo trùng.
 */
@Component
@RequiredArgsConstructor
public class DelinquencyEventsHandler implements InboundEventHandler {

    static final EventKey DELINQUENCY_CHANGED = new EventKey("LoanDelinquencyChanged", 1);

    private final EventPayloads payloads;
    private final LoanRecipientService recipients;
    private final NotificationText text;

    @Override
    public Set<EventKey> keys() {
        return Set.of(DELINQUENCY_CHANGED);
    }

    @Override
    public HandlerOutcome handle(InboundEvent event) {
        var data = payloads.read(event, LoanServicingEvents.DelinquencyChanged.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        String borrowerId = required(data.borrowerId(), "borrowerId", event.eventType());
        if (data.daysPastDue() < 0 || data.debtGroup() < 1 || data.debtGroup() > 5) {
            throw new IllegalArgumentException("LoanDelinquencyChanged có DPD/nhóm nợ ngoài miền hợp lệ");
        }
        // Mọi event đều cập nhật bảng tra (event này mang borrowerId và mã khoản vay) dù không sinh thông báo.
        LoanReference reference = recipients.remember(loanId, borrowerId, null, null, data.loanNumber(),
                event.eventId());
        var template = DelinquencyNotificationPolicy.decide(data.previousDaysPastDue(), data.daysPastDue(),
                data.previousDebtGroup(), data.debtGroup());
        if (template.isEmpty()) return HandlerOutcome.filtered("SAME_DELINQUENCY_STAGE");
        Map<String, String> values = Map.of(
                "loan", text.loanLabel(loanId, reference, data.loanNumber(), null, null),
                "dpd", Integer.toString(data.daysPastDue()),
                "overdue", text.moneyOr(data.overdueAmount(), "0.00"),
                "group", Integer.toString(data.debtGroup()),
                "stage", DelinquencyNotificationPolicy.stageLabel(data.debtGroup()));
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(reference.getBorrowerId(), template.get(), values,
                loanReference(loanId))));
    }
}
