package com.finora.notification.service.handler;

import static com.finora.notification.service.handler.HandlerChecks.hasText;
import static com.finora.notification.service.handler.HandlerChecks.loanId;
import static com.finora.notification.service.handler.HandlerChecks.loanReference;
import static com.finora.notification.service.handler.HandlerChecks.notEmpty;
import static com.finora.notification.service.handler.HandlerChecks.required;

import com.finora.notification.domain.LoanReference;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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

    private final EventPayloads payloads;
    private final LoanRecipientService recipients;
    private final NotificationText text;

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
        var data = payloads.read(event, LoanFundingEvents.FundingRequested.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        // Producer trước 2026-10-11 không gửi borrowerId: không biết người vay thì không có ai để báo.
        if (!hasText(data.borrowerId())) return HandlerOutcome.deliver(List.of());
        LoanReference reference = recipients.remember(loanId, data.borrowerId(), data.applicationNumber(), null, null,
                event.eventId());
        String amount = required(text.money(data.targetAmount()), "targetAmount", event.eventType());
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(reference.getBorrowerId(),
                NotificationTemplate.LOAN_FUNDING_OPENED,
                Map.of("application", text.applicationLabel(loanId, reference, data.applicationNumber()),
                        "amount", amount),
                loanReference(loanId))));
    }

    private HandlerOutcome fullyFunded(InboundEvent event) {
        var data = payloads.read(event, LoanFundingEvents.FullyFunded.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        Long allocationVersion = required(data.allocationVersion(), "allocationVersion", event.eventType());
        notEmpty(data.allocations(), "allocations", event.eventType());
        List<String> investorIds = data.allocations().stream()
                .map(allocation -> required(allocation.investorId(), "allocations.investorId", event.eventType()))
                .distinct().toList();
        recipients.replaceLenders(loanId, allocationVersion, investorIds, data.applicationNumber(), event.eventId());
        Optional<String> borrower = recipients.borrower(loanId, null, data.applicationNumber(), null, null,
                event.eventId());
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", List.of());
        LoanReference reference = recipients.find(loanId).orElse(null);
        String amount = required(text.money(data.fundedAmount()), "fundedAmount", event.eventType());
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrower.get(),
                NotificationTemplate.LOAN_FULLY_FUNDED,
                Map.of("application", text.applicationLabel(loanId, reference, data.applicationNumber()),
                        "amount", amount),
                loanReference(loanId))));
    }

    private HandlerOutcome fundingExpired(InboundEvent event) {
        var data = payloads.read(event, LoanFundingEvents.FundingExpired.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        Optional<String> borrower = recipients.borrower(loanId, null, data.applicationNumber(), null, null,
                event.eventId());
        LoanReference reference = recipients.find(loanId).orElse(null);
        String application = text.applicationLabel(loanId, reference, data.applicationNumber());
        List<NotificationDraft> drafts = new ArrayList<>();
        // investorIds là field đề xuất (P6): khi Investment bổ sung, nhà đầu tư được báo tiền giữ đã về ví.
        if (data.investorIds() != null) {
            data.investorIds().stream().filter(HandlerChecks::hasText).distinct().forEach(investorId ->
                    drafts.add(NotificationDraft.of(investorId, NotificationTemplate.INVESTMENT_FUNDING_EXPIRED,
                            Map.of("application", application), loanReference(loanId))));
        }
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", drafts);
        drafts.add(0, NotificationDraft.of(borrower.get(), NotificationTemplate.LOAN_FUNDING_EXPIRED,
                Map.of("application", application,
                        "committed", text.moneyOr(data.committedAmount(), "0.00"),
                        "amount", text.moneyOr(data.targetAmount(), "0.00")),
                loanReference(loanId)));
        return HandlerOutcome.deliver(drafts);
    }

    private HandlerOutcome fundingCancelled(InboundEvent event) {
        var data = payloads.read(event, LoanFundingEvents.FundingCancelled.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        String reasonCode = required(data.reasonCode(), "reasonCode", event.eventType());
        Optional<String> borrower = recipients.borrower(loanId, data.borrowerId(), data.applicationNumber(),
                data.contractNumber(), null, event.eventId());
        LoanRecipientService.Lenders lenders = recipients.lenders(loanId);
        if (lenders.supersedes(data.allocationVersion())) return HandlerOutcome.filtered("STALE_ALLOCATION");
        LoanReference reference = recipients.find(loanId).orElse(null);
        String reason = reason(reasonCode, text.contractLabel(reference, data.contractNumber()));
        String loan = text.loanLabel(loanId, reference, null, null, data.applicationNumber());
        List<NotificationDraft> drafts = new ArrayList<>();
        borrower.ifPresent(id -> drafts.add(NotificationDraft.of(id, NotificationTemplate.LOAN_FUNDING_CANCELLED,
                Map.of("reason", reason, "loan", loan), loanReference(loanId))));
        boolean lendersKnown = lenders.matches(data.allocationVersion());
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
            case "CONTRACT_DECLINED" -> "Hợp đồng " + contract + " đã bị từ chối ký.";
            case "CONTRACT_EXPIRED" -> "Hợp đồng " + contract + " đã hết hạn ký.";
            case "DISBURSEMENT_FAILED" -> "Giải ngân không thực hiện được.";
            default -> "Đợt gọi vốn đã bị hủy.";
        };
    }
}
