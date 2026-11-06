package com.finora.notification.service.handler.impl;

import com.finora.notification.domain.entity.LoanReference;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.finora.notification.service.handler.impl.HandlerChecks.loanId;
import static com.finora.notification.service.handler.impl.HandlerChecks.loanReference;
import static com.finora.notification.service.handler.impl.HandlerChecks.required;

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

    private final EventPayloads eventPayloads;
    private final LoanRecipientService loanRecipientService;
    private final DueScheduleService dueScheduleService;
    private final NotificationText notificationText;

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
        var payload = eventPayloads.read(event, LoanServicingEvents.RepaymentDistributed.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        String amount = required(notificationText.money(payload.amount()), "amount", event.eventType());
        Optional<String> borrower = loanRecipientService.borrower(loanId, payload.borrowerId(), null, null, null,
                event.eventId());
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", List.of());
        BigDecimal outstanding = notificationText.decimal(payload.totalOutstanding());
        boolean paidOff = outstanding != null && outstanding.signum() == 0;
        LoanReference reference = loanRecipientService.find(loanId).orElse(null);
        BigDecimal nextDueAmount = paidOff ? null : notificationText.decimal(payload.nextDueAmount());
        dueScheduleService.applyNextDue(loanId, borrower.get(), payload.currency(),
                paidOff ? null : payload.nextDueDate(), nextDueAmount, event.eventId(),
                required(payload.completedAt(), "completedAt", event.eventType()));
        if (payload.repaymentId() != null) dueScheduleService.releaseHeld(loanId, payload.repaymentId());
        String next = paidOff || payload.nextDueDate() == null
                ? "Khoản vay không còn kỳ nào phải trả."
                : "Kỳ tiếp theo đến hạn ngày " + notificationText.date(payload.nextDueDate())
                        + (nextDueAmount == null ? "."
                                : ", số tiền " + notificationText.money(nextDueAmount) + " VND.");
        NotificationDraft draft = NotificationDraft.of(borrower.get(), NotificationTemplate.REPAYMENT_RECORDED,
                Map.of("amount", amount, "loan", notificationText.loanLabel(loanId, reference, null, null, null),
                        "next", next),
                loanReference(loanId));
        return HandlerOutcome.deliver(List.of(paidOff ? draft.withoutPush() : draft));
    }

    private HandlerOutcome settled(InboundEvent event) {
        var payload = eventPayloads.read(event, LoanServicingEvents.Settled.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        Optional<String> borrower = loanRecipientService.borrower(loanId, payload.borrowerId(), null,
                payload.contractNumber(), payload.loanNumber(), event.eventId());
        dueScheduleService.close(loanId, event.eventId(),
                payload.settledAt() == null ? event.occurredAt() : payload.settledAt());
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", List.of());
        LoanReference reference = loanRecipientService.find(loanId).orElse(null);
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrower.get(), NotificationTemplate.LOAN_SETTLED,
                Map.of("loan", notificationText.loanLabel(loanId, reference, payload.loanNumber(),
                        payload.contractNumber(), null)),
                loanReference(loanId))));
    }

    private HandlerOutcome collected(InboundEvent event) {
        var payload = eventPayloads.read(event, PaymentWalletEvents.RepaymentCollected.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        required(payload.repaymentId(), "repaymentId", event.eventType());
        // Chỉ khoản trả kỳ được giữ tới ngày đến hạn mới cần tin riêng; loại khác ghi Fineract ngay và có
        // RepaymentDistributed theo sau.
        if (!"SCHEDULED".equals(payload.repaymentType()) || payload.dueDate() == null) {
            return HandlerOutcome.filtered("NOT_HELD_INSTALLMENT");
        }
        String borrowerId = required(payload.borrowerId(), "borrowerId", event.eventType());
        loanRecipientService.borrower(loanId, borrowerId, null, null, null, event.eventId());
        dueScheduleService.markHeld(loanId, borrowerId, payload.currency(), payload.dueDate(), payload.repaymentId(),
                payload.collectedAt() == null ? event.occurredAt() : payload.collectedAt(), event.eventId());
        LoanReference reference = loanRecipientService.find(loanId).orElse(null);
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrowerId,
                NotificationTemplate.INSTALLMENT_PAYMENT_HELD,
                Map.of("amount", required(notificationText.money(payload.amount()), "amount", event.eventType()),
                        "dueDate", notificationText.date(payload.dueDate()),
                        "loan", notificationText.loanLabel(loanId, reference, null, null, null)),
                loanReference(loanId))));
    }

    private HandlerOutcome cancelled(InboundEvent event) {
        var payload = eventPayloads.read(event, PaymentWalletEvents.RepaymentCancelled.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        required(payload.repaymentId(), "repaymentId", event.eventType());
        String borrowerId = required(payload.borrowerId(), "borrowerId", event.eventType());
        loanRecipientService.borrower(loanId, borrowerId, null, null, null, event.eventId());
        dueScheduleService.releaseHeld(loanId, payload.repaymentId());
        LoanReference reference = loanRecipientService.find(loanId).orElse(null);
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrowerId,
                NotificationTemplate.INSTALLMENT_PAYMENT_CANCELLED,
                Map.of("dueDate", notificationText.date(payload.dueDate()),
                        "loan", notificationText.loanLabel(loanId, reference, null, null, null)),
                loanReference(loanId))));
    }
}
