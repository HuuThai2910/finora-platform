package com.finora.notification.service.handler.impl;

import com.finora.notification.domain.entity.LoanReference;
import com.finora.notification.messaging.event.LoanContractEvents;
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

import static com.finora.notification.service.handler.impl.HandlerChecks.loanId;
import static com.finora.notification.service.handler.impl.HandlerChecks.loanReference;

/**
 * Ký hợp đồng nhiều bên (F03): mời nhà đầu tư ký, đến lượt người vay ký (sửa B24), hợp đồng có hiệu lực.
 * Event của Loan không mang người nhận (B64): nhà đầu tư lấy theo allocation đã lưu và chỉ khi đúng
 * {@code allocationVersion} của hợp đồng; người vay lấy từ event khi producer bổ sung {@code borrowerId} (P1/P2), nếu
 * chưa thì từ bảng tra.
 */
@Component
@RequiredArgsConstructor
public class ContractEventsHandler implements InboundEventHandler {

    static final EventKey INVESTOR_SIGNATURE_REQUESTED = new EventKey("InvestorSignatureRequested", 1);
    static final EventKey BORROWER_SIGNATURE_REQUESTED = new EventKey("BorrowerSignatureRequested", 1);
    static final EventKey CONTRACT_ACTIVATED = new EventKey("LoanContractActivated", 1);

    private final EventPayloads eventPayloads;
    private final LoanRecipientService loanRecipientService;
    private final NotificationText notificationText;

    @Override
    public Set<EventKey> keys() {
        return Set.of(INVESTOR_SIGNATURE_REQUESTED, BORROWER_SIGNATURE_REQUESTED, CONTRACT_ACTIVATED);
    }

    @Override
    public HandlerOutcome handle(InboundEvent event) {
        return switch (event.eventType()) {
            case "InvestorSignatureRequested" -> investorSignatureRequested(event);
            case "BorrowerSignatureRequested" -> borrowerSignatureRequested(event);
            case "LoanContractActivated" -> contractActivated(event);
            default -> throw new IllegalArgumentException("ContractEventsHandler không nhận " + event.eventType());
        };
    }

    private HandlerOutcome investorSignatureRequested(InboundEvent event) {
        var payload = eventPayloads.read(event, LoanContractEvents.InvestorSignatureRequested.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        LoanReference reference = loanRecipientService.remember(loanId, null, payload.applicationNumber(),
                payload.contractNumber(), null, event.eventId());
        LoanRecipientService.Lenders lenders = loanRecipientService.lenders(loanId);
        if (lenders.supersedes(payload.allocationVersion())) return HandlerOutcome.filtered("STALE_ALLOCATION");
        if (!lenders.matches(payload.allocationVersion())) return HandlerOutcome.await("LENDERS_UNKNOWN", List.of());
        Map<String, String> values = Map.of(
                "application", notificationText.applicationLabel(loanId, reference, payload.applicationNumber()),
                "contract", notificationText.contractLabel(reference, payload.contractNumber()),
                "deadline", notificationText.dateTime(payload.expiresAt()));
        return HandlerOutcome.deliver(lenders.investorIds().stream()
                .map(investorId -> NotificationDraft.of(investorId, NotificationTemplate.INVESTOR_SIGNATURE_REQUESTED,
                        values, loanReference(loanId)))
                .toList());
    }

    private HandlerOutcome borrowerSignatureRequested(InboundEvent event) {
        var payload = eventPayloads.read(event, LoanContractEvents.BorrowerSignatureRequested.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        Optional<String> borrower = loanRecipientService.borrower(loanId, payload.borrowerId(),
                payload.applicationNumber(), payload.contractNumber(), null, event.eventId());
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", List.of());
        LoanReference reference = loanRecipientService.find(loanId).orElse(null);
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrower.get(),
                NotificationTemplate.BORROWER_SIGNATURE_REQUESTED,
                Map.of("lenderCount", payload.lenderCount() == null ? "đã đủ" : payload.lenderCount().toString(),
                        "contract", notificationText.contractLabel(reference, payload.contractNumber()),
                        "deadline", notificationText.dateTime(payload.expiresAt())),
                loanReference(loanId))));
    }

    private HandlerOutcome contractActivated(InboundEvent event) {
        var payload = eventPayloads.read(event, LoanContractEvents.ContractActivated.class);
        Long loanId = loanId(payload.loanApplicationId(), event.eventType());
        Optional<String> borrower = loanRecipientService.borrower(loanId, payload.borrowerId(),
                payload.applicationNumber(), payload.contractNumber(), null, event.eventId());
        LoanRecipientService.Lenders lenders = loanRecipientService.lenders(loanId);
        if (lenders.supersedes(payload.allocationVersion())) return HandlerOutcome.filtered("STALE_ALLOCATION");
        LoanReference reference = loanRecipientService.find(loanId).orElse(null);
        Map<String, String> values = Map.of("contract",
                notificationText.contractLabel(reference, payload.contractNumber()));
        List<NotificationDraft> drafts = new ArrayList<>();
        borrower.ifPresent(id -> drafts.add(NotificationDraft.of(id, NotificationTemplate.CONTRACT_ACTIVATED, values,
                loanReference(loanId))));
        boolean lendersKnown = lenders.matches(payload.allocationVersion());
        if (lendersKnown) {
            lenders.investorIds().forEach(investorId -> drafts.add(NotificationDraft.of(investorId,
                    NotificationTemplate.INVESTOR_CONTRACT_ACTIVATED, values, loanReference(loanId))));
        }
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", drafts);
        if (!lendersKnown) return HandlerOutcome.await("LENDERS_UNKNOWN", drafts);
        return HandlerOutcome.deliver(drafts);
    }
}
