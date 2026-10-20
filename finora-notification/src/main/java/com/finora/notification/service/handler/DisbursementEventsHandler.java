package com.finora.notification.service.handler;

import static com.finora.notification.service.handler.HandlerChecks.loanId;
import static com.finora.notification.service.handler.HandlerChecks.loanReference;
import static com.finora.notification.service.handler.HandlerChecks.required;

import com.finora.notification.domain.LoanReference;
import com.finora.notification.messaging.event.LoanContractEvents;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code LoanDisbursed.v1}: Payment đã chuyển tiền và Fineract đã ghi khoản vay (F05). Người vay nhận tin tiền đã vào
 * ví; nhà đầu tư của allocation nhận tin Note đã phát hành. Khi Loan bổ sung kỳ đầu (P3) thì lịch nhắc bắt đầu ngay từ kỳ
 * 1 thay vì từ lần trả nợ đầu tiên.
 */
@Component
@RequiredArgsConstructor
public class DisbursementEventsHandler implements InboundEventHandler {

    static final EventKey LOAN_DISBURSED = new EventKey("LoanDisbursed", 1);

    private final EventPayloads payloads;
    private final LoanRecipientService recipients;
    private final DueScheduleService dueSchedules;
    private final NotificationText text;

    @Override
    public Set<EventKey> keys() {
        return Set.of(LOAN_DISBURSED);
    }

    @Override
    public HandlerOutcome handle(InboundEvent event) {
        var data = payloads.read(event, LoanContractEvents.Disbursed.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        String amount = required(text.money(data.amount()), "amount", event.eventType());
        Optional<String> borrower = recipients.borrower(loanId, data.borrowerId(), data.applicationNumber(),
                data.contractNumber(), data.loanNumber(), event.eventId());
        LoanRecipientService.Lenders lenders = recipients.lenders(loanId);
        LoanReference reference = recipients.find(loanId).orElse(null);
        String loan = text.loanLabel(loanId, reference, data.loanNumber(), data.contractNumber(),
                data.applicationNumber());
        List<NotificationDraft> drafts = new ArrayList<>();
        borrower.ifPresent(id -> {
            drafts.add(NotificationDraft.of(id, NotificationTemplate.LOAN_DISBURSED,
                    Map.of("amount", amount, "loan", loan), loanReference(loanId)));
            if (data.nextDueDate() != null) {
                BigDecimal nextDueAmount = text.decimal(data.nextDueAmount());
                dueSchedules.applyNextDue(loanId, id, data.currency(), data.nextDueDate(), nextDueAmount,
                        event.eventId(), required(data.disbursedAt(), "disbursedAt", event.eventType()));
            }
        });
        // LoanDisbursed không mang allocationVersion: dùng allocation đang lưu (giải ngân chỉ xảy ra với allocation đã ký).
        if (lenders.known()) {
            lenders.investorIds().forEach(investorId -> drafts.add(NotificationDraft.of(investorId,
                    NotificationTemplate.NOTES_ISSUED, Map.of("loan", loan), loanReference(loanId))));
        }
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", drafts);
        if (!lenders.known()) return HandlerOutcome.await("LENDERS_UNKNOWN", drafts);
        return HandlerOutcome.deliver(drafts);
    }
}
