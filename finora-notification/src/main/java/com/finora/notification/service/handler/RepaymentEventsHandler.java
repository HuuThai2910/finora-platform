package com.finora.notification.service.handler;

import static com.finora.notification.service.handler.HandlerChecks.loanId;
import static com.finora.notification.service.handler.HandlerChecks.loanReference;
import static com.finora.notification.service.handler.HandlerChecks.required;

import com.finora.notification.domain.LoanReference;
import com.finora.notification.messaging.event.LoanServicingEvents;
import com.finora.notification.messaging.event.PaymentWalletEvents;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Trả nợ của người vay (F06, D64). Nhà đầu tư nhận tin tiền về từ Investment ({@code REPAYMENT_CREDITED}), nên ở đây chỉ
 * báo người vay và cập nhật lịch nhắc:
 * <ul>
 *   <li>{@code RepaymentDistributed}: Fineract đã ghi khoản trả → báo đã ghi nhận, kỳ tiếp theo theo Fineract. Khoản trả
 *       hết nợ không push vì {@code LoanSettled} ngay sau sẽ push.</li>
 *   <li>{@code LoanSettled}: đóng lịch nhắc, báo tất toán.</li>
 *   <li>{@code RepaymentCollected}/{@code RepaymentCancelled} (đề xuất P5): khoản trả kỳ trước hạn đang giữ (D16) và lúc
 *       người vay hủy nó. Có cờ giữ thì không nhắc kỳ đó.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RepaymentEventsHandler implements InboundEventHandler {

    static final EventKey REPAYMENT_DISTRIBUTED = new EventKey("RepaymentDistributed", 1);
    static final EventKey LOAN_SETTLED = new EventKey("LoanSettled", 1);
    static final EventKey REPAYMENT_COLLECTED = new EventKey("RepaymentCollected", 1);
    static final EventKey REPAYMENT_CANCELLED = new EventKey("RepaymentCancelled", 1);

    private final EventPayloads payloads;
    private final LoanRecipientService recipients;
    private final DueScheduleService dueSchedules;
    private final NotificationText text;

    @Override
    public Set<EventKey> keys() {
        return Set.of(REPAYMENT_DISTRIBUTED, LOAN_SETTLED, REPAYMENT_COLLECTED, REPAYMENT_CANCELLED);
    }

    @Override
    public HandlerOutcome handle(InboundEvent event) {
        return switch (event.eventType()) {
            case "RepaymentDistributed" -> distributed(event);
            case "LoanSettled" -> settled(event);
            case "RepaymentCollected" -> collected(event);
            case "RepaymentCancelled" -> cancelled(event);
            default -> throw new IllegalArgumentException("RepaymentEventsHandler không nhận " + event.eventType());
        };
    }

    private HandlerOutcome distributed(InboundEvent event) {
        var data = payloads.read(event, LoanServicingEvents.RepaymentDistributed.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        String amount = required(text.money(data.amount()), "amount", event.eventType());
        Optional<String> borrower = recipients.borrower(loanId, data.borrowerId(), null, null, null, event.eventId());
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", List.of());
        BigDecimal outstanding = text.decimal(data.totalOutstanding());
        boolean paidOff = outstanding != null && outstanding.signum() == 0;
        LoanReference reference = recipients.find(loanId).orElse(null);
        BigDecimal nextDueAmount = paidOff ? null : text.decimal(data.nextDueAmount());
        dueSchedules.applyNextDue(loanId, borrower.get(), data.currency(), paidOff ? null : data.nextDueDate(),
                nextDueAmount, event.eventId(), required(data.completedAt(), "completedAt", event.eventType()));
        if (data.repaymentId() != null) dueSchedules.releaseHeld(loanId, data.repaymentId());
        String next = paidOff || data.nextDueDate() == null
                ? "Khoản vay không còn kỳ nào phải trả."
                : "Kỳ tiếp theo đến hạn ngày " + text.date(data.nextDueDate())
                        + (nextDueAmount == null ? "." : ", số tiền " + text.money(nextDueAmount) + " VND.");
        NotificationDraft draft = NotificationDraft.of(borrower.get(), NotificationTemplate.REPAYMENT_RECORDED,
                Map.of("amount", amount, "loan", text.loanLabel(loanId, reference, null, null, null), "next", next),
                loanReference(loanId));
        return HandlerOutcome.deliver(List.of(paidOff ? draft.withoutPush() : draft));
    }

    private HandlerOutcome settled(InboundEvent event) {
        var data = payloads.read(event, LoanServicingEvents.Settled.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        Optional<String> borrower = recipients.borrower(loanId, data.borrowerId(), null, data.contractNumber(),
                data.loanNumber(), event.eventId());
        dueSchedules.close(loanId, event.eventId(), data.settledAt() == null ? event.occurredAt() : data.settledAt());
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", List.of());
        LoanReference reference = recipients.find(loanId).orElse(null);
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrower.get(), NotificationTemplate.LOAN_SETTLED,
                Map.of("loan", text.loanLabel(loanId, reference, data.loanNumber(), data.contractNumber(), null)),
                loanReference(loanId))));
    }

    private HandlerOutcome collected(InboundEvent event) {
        var data = payloads.read(event, PaymentWalletEvents.RepaymentCollected.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        required(data.repaymentId(), "repaymentId", event.eventType());
        // Chỉ khoản trả kỳ được giữ tới ngày đến hạn mới cần tin riêng; loại khác ghi Fineract ngay và có
        // RepaymentDistributed theo sau.
        if (!"SCHEDULED".equals(data.repaymentType()) || data.dueDate() == null) {
            return HandlerOutcome.filtered("NOT_HELD_INSTALLMENT");
        }
        String borrowerId = required(data.borrowerId(), "borrowerId", event.eventType());
        recipients.borrower(loanId, borrowerId, null, null, null, event.eventId());
        dueSchedules.markHeld(loanId, borrowerId, data.currency(), data.dueDate(), data.repaymentId(),
                data.collectedAt() == null ? event.occurredAt() : data.collectedAt(), event.eventId());
        LoanReference reference = recipients.find(loanId).orElse(null);
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrowerId,
                NotificationTemplate.INSTALLMENT_PAYMENT_HELD,
                Map.of("amount", required(text.money(data.amount()), "amount", event.eventType()),
                        "dueDate", text.date(data.dueDate()),
                        "loan", text.loanLabel(loanId, reference, null, null, null)),
                loanReference(loanId))));
    }

    private HandlerOutcome cancelled(InboundEvent event) {
        var data = payloads.read(event, PaymentWalletEvents.RepaymentCancelled.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        required(data.repaymentId(), "repaymentId", event.eventType());
        String borrowerId = required(data.borrowerId(), "borrowerId", event.eventType());
        recipients.borrower(loanId, borrowerId, null, null, null, event.eventId());
        dueSchedules.releaseHeld(loanId, data.repaymentId());
        LoanReference reference = recipients.find(loanId).orElse(null);
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrowerId,
                NotificationTemplate.INSTALLMENT_PAYMENT_CANCELLED,
                Map.of("dueDate", text.date(data.dueDate()), "loan", text.loanLabel(loanId, reference, null, null, null)),
                loanReference(loanId))));
    }
}
