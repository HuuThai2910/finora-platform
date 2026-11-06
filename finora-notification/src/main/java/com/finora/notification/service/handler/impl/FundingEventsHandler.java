package com.finora.notification.service.handler.impl;

import com.finora.notification.domain.entity.LoanReference;
import com.finora.notification.messaging.event.LoanFundingEvents;
import com.finora.notification.service.delivery.NotificationDraft;
import com.finora.notification.service.intake.EventKey;
import com.finora.notification.service.intake.EventPayloads;
import com.finora.notification.service.intake.HandlerOutcome;
import com.finora.notification.service.intake.InboundEvent;
import com.finora.notification.service.intake.InboundEventHandler;
import com.finora.notification.service.recipient.LoanRecipientService;
import com.finora.notification.service.template.NotificationTemplate;
import com.finora.notification.service.template.NotificationText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.finora.notification.service.handler.impl.HandlerChecks.hasText;
import static com.finora.notification.service.handler.impl.HandlerChecks.loanId;
import static com.finora.notification.service.handler.impl.HandlerChecks.loanReference;
import static com.finora.notification.service.handler.impl.HandlerChecks.notEmpty;
import static com.finora.notification.service.handler.impl.HandlerChecks.required;

/**
 * Đợt gọi vốn: lên sàn, đủ vốn, hết hạn, bị hủy sau khi đủ vốn.
 * <p>
 * {@code LoanFundingRequested} là nguồn {@code borrowerId} cho bảng tra; {@code LoanFullyFunded} là nguồn danh sách nhà
 * đầu tư của allocation đã khóa. Nhà đầu tư không nhận tin "đủ vốn" riêng vì ngay sau đó Loan phát
 * {@code InvestorSignatureRequested} mời họ ký (một tin thay vì hai tin trong vài giây).
 */
@Component
@RequiredArgsConstructor
public class FundingEventsHandler implements InboundEventHandler {

    static final EventKey FUNDING_REQUESTED = new EventKey("LoanFundingRequested", 1);
    static final EventKey FULLY_FUNDED = new EventKey("LoanFullyFunded", 2);
    static final EventKey FUNDING_EXPIRED = new EventKey("LoanFundingExpired", 1);
    static final EventKey FUNDING_CANCELLED = new EventKey("LoanFundingCancelled", 1);

    private final EventPayloads eventPayloads;
    private final LoanRecipientService loanRecipientService;
    private final NotificationText notificationText;

    @Override
    public Set<EventKey> keys() {
        return Set.of(FUNDING_REQUESTED, FULLY_FUNDED, FUNDING_EXPIRED, FUNDING_CANCELLED);
    }

    @Override
    public HandlerOutcome handle(InboundEvent event) {
        return switch (event.eventType()) {
            case "LoanFundingRequested" -> fundingRequested(event);
            case "LoanFullyFunded" -> fullyFunded(event);
            case "LoanFundingExpired" -> fundingExpired(event);
            case "LoanFundingCancelled" -> fundingCancelled(event);
            default -> throw new IllegalArgumentException("FundingEventsHandler không nhận " + event.eventType());
        };
    }

    private HandlerOutcome fundingRequested(InboundEvent event) {
        var payload = eventPayloads.read(event, LoanFundingEvents.FundingRequested.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        // Producer trước 2026-10-11 không gửi borrowerId: không biết người vay thì không có ai để báo.
        if (!hasText(payload.borrowerId())) return HandlerOutcome.deliver(List.of());
        LoanReference reference = loanRecipientService.remember(loanId, payload.borrowerId(),
                payload.applicationNumber(), null, null, event.eventId());
        String amount = required(notificationText.money(payload.targetAmount()), "targetAmount", event.eventType());
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(reference.getBorrowerId(),
                NotificationTemplate.LOAN_FUNDING_OPENED,
                Map.of("application", notificationText.applicationLabel(loanId, reference, payload.applicationNumber()),
                        "amount", amount),
                loanReference(loanId))));
    }

    private HandlerOutcome fullyFunded(InboundEvent event) {
        var payload = eventPayloads.read(event, LoanFundingEvents.FullyFunded.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        Long allocationVersion = required(payload.allocationVersion(), "allocationVersion", event.eventType());
        notEmpty(payload.allocations(), "allocations", event.eventType());
        List<String> investorIds = payload.allocations().stream()
                .map(allocation -> required(allocation.investorId(), "allocations.investorId", event.eventType()))
                .distinct().toList();
        loanRecipientService.replaceLenders(loanId, allocationVersion, investorIds, payload.applicationNumber(),
                event.eventId());
        Optional<String> borrower = loanRecipientService.borrower(loanId, null, payload.applicationNumber(), null, null,
                event.eventId());
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", List.of());
        LoanReference reference = loanRecipientService.find(loanId).orElse(null);
        String amount = required(notificationText.money(payload.fundedAmount()), "fundedAmount", event.eventType());
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrower.get(),
                NotificationTemplate.LOAN_FULLY_FUNDED,
                Map.of("application", notificationText.applicationLabel(loanId, reference, payload.applicationNumber()),
                        "amount", amount),
                loanReference(loanId))));
    }

    private HandlerOutcome fundingExpired(InboundEvent event) {
        var payload = eventPayloads.read(event, LoanFundingEvents.FundingExpired.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        Optional<String> borrower = loanRecipientService.borrower(loanId, null, payload.applicationNumber(), null, null,
                event.eventId());
        LoanReference reference = loanRecipientService.find(loanId).orElse(null);
        String application = notificationText.applicationLabel(loanId, reference, payload.applicationNumber());
        List<NotificationDraft> drafts = new ArrayList<>();
        // investorIds là field đề xuất (P6): khi Investment bổ sung, nhà đầu tư được báo tiền giữ đã về ví.
        if (payload.investorIds() != null) {
            payload.investorIds().stream().filter(HandlerChecks::hasText).distinct().forEach(investorId ->
                    drafts.add(NotificationDraft.of(investorId, NotificationTemplate.INVESTMENT_FUNDING_EXPIRED,
                            Map.of("application", application), loanReference(loanId))));
        }
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", drafts);
        drafts.add(0, NotificationDraft.of(borrower.get(), NotificationTemplate.LOAN_FUNDING_EXPIRED,
                Map.of("application", application,
                        "committed", notificationText.moneyOr(payload.committedAmount(), "0.00"),
                        "amount", notificationText.moneyOr(payload.targetAmount(), "0.00")),
                loanReference(loanId)));
        return HandlerOutcome.deliver(drafts);
    }

    private HandlerOutcome fundingCancelled(InboundEvent event) {
        var payload = eventPayloads.read(event, LoanFundingEvents.FundingCancelled.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        String reasonCode = required(payload.reasonCode(), "reasonCode", event.eventType());
        Optional<String> borrower = loanRecipientService.borrower(loanId, payload.borrowerId(),
                payload.applicationNumber(), payload.contractNumber(), null, event.eventId());
        LoanReference reference = loanRecipientService.find(loanId).orElse(null);
        String reason = reason(reasonCode, notificationText.contractLabel(reference, payload.contractNumber()));
        String loan = notificationText.loanLabel(loanId, reference, null, null, payload.applicationNumber());
        List<NotificationDraft> drafts = new ArrayList<>();
        borrower.ifPresent(id -> drafts.add(NotificationDraft.of(id, NotificationTemplate.LOAN_FUNDING_CANCELLED,
                Map.of("reason", reason, "loan", loan), loanReference(loanId))));

        // Rút khi listing còn OPEN chưa có allocationVersion và chưa có danh sách nhà đầu tư đã khóa.
        // Chỉ báo cho người vay; không chờ một projection lender không thể tồn tại và không tạo người nhận giả.
        if ("BORROWER_WITHDREW".equals(reasonCode)) {
            return borrower.isEmpty()
                    ? HandlerOutcome.await("BORROWER_UNKNOWN", drafts)
                    : HandlerOutcome.deliver(drafts);
        }

        Long allocationVersion = required(payload.allocationVersion(), "allocationVersion", event.eventType());
        LoanRecipientService.Lenders lenders = loanRecipientService.lenders(loanId);
        if (lenders.supersedes(allocationVersion)) return HandlerOutcome.filtered("STALE_ALLOCATION");
        boolean lendersKnown = lenders.matches(allocationVersion);
        if (lendersKnown) {
            lenders.investorIds().forEach(investorId -> drafts.add(NotificationDraft.of(investorId,
                    NotificationTemplate.INVESTMENT_FUNDING_CANCELLED, Map.of("reason", reason, "loan", loan),
                    loanReference(loanId))));
        }
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", drafts);
        if (!lendersKnown) return HandlerOutcome.await("LENDERS_UNKNOWN", drafts);
        return HandlerOutcome.deliver(drafts);
    }

    private static String reason(String reasonCode, String contract) {
        return switch (reasonCode) {
            case "BORROWER_WITHDREW" -> "Bạn đã rút hồ sơ trước khi hợp đồng được ký.";
            case "CONTRACT_DECLINED" -> "Hợp đồng " + contract + " đã bị từ chối ký.";
            case "CONTRACT_EXPIRED" -> "Hợp đồng " + contract + " đã hết hạn ký.";
            case "DISBURSEMENT_FAILED" -> "Giải ngân không thực hiện được.";
            default -> "Đợt gọi vốn đã bị hủy.";
        };
    }
}
