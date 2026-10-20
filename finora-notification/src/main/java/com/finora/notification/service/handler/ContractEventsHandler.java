package com.finora.notification.service.handler;

import static com.finora.notification.service.handler.HandlerChecks.loanId;
import static com.finora.notification.service.handler.HandlerChecks.loanReference;

import com.finora.notification.domain.LoanReference;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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

    private final EventPayloads payloads;
    private final LoanRecipientService recipients;
    private final NotificationText text;

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
        var data = payloads.read(event, LoanContractEvents.InvestorSignatureRequested.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        LoanReference reference = recipients.remember(loanId, null, data.applicationNumber(), data.contractNumber(),
                null, event.eventId());
        LoanRecipientService.Lenders lenders = recipients.lenders(loanId);
        if (lenders.supersedes(data.allocationVersion())) return HandlerOutcome.filtered("STALE_ALLOCATION");
        if (!lenders.matches(data.allocationVersion())) return HandlerOutcome.await("LENDERS_UNKNOWN", List.of());
        Map<String, String> values = Map.of(
                "application", text.applicationLabel(loanId, reference, data.applicationNumber()),
                "contract", text.contractLabel(reference, data.contractNumber()),
                "deadline", text.dateTime(data.expiresAt()));
        return HandlerOutcome.deliver(lenders.investorIds().stream()
                .map(investorId -> NotificationDraft.of(investorId, NotificationTemplate.INVESTOR_SIGNATURE_REQUESTED,
                        values, loanReference(loanId)))
                .toList());
    }

    private HandlerOutcome borrowerSignatureRequested(InboundEvent event) {
        var data = payloads.read(event, LoanContractEvents.BorrowerSignatureRequested.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        Optional<String> borrower = recipients.borrower(loanId, data.borrowerId(), data.applicationNumber(),
                data.contractNumber(), null, event.eventId());
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", List.of());
        LoanReference reference = recipients.find(loanId).orElse(null);
        return HandlerOutcome.deliver(List.of(NotificationDraft.of(borrower.get(),
                NotificationTemplate.BORROWER_SIGNATURE_REQUESTED,
                Map.of("lenderCount", data.lenderCount() == null ? "đã đủ" : data.lenderCount().toString(),
                        "contract", text.contractLabel(reference, data.contractNumber()),
                        "deadline", text.dateTime(data.expiresAt())),
                loanReference(loanId))));
    }

    private HandlerOutcome contractActivated(InboundEvent event) {
        var data = payloads.read(event, LoanContractEvents.ContractActivated.class);
        Long loanId = loanId(data.loanApplicationId(), event.eventType());
        Optional<String> borrower = recipients.borrower(loanId, data.borrowerId(), data.applicationNumber(),
                data.contractNumber(), null, event.eventId());
        LoanRecipientService.Lenders lenders = recipients.lenders(loanId);
        if (lenders.supersedes(data.allocationVersion())) return HandlerOutcome.filtered("STALE_ALLOCATION");
        LoanReference reference = recipients.find(loanId).orElse(null);
        Map<String, String> values = Map.of("contract", text.contractLabel(reference, data.contractNumber()));
        List<NotificationDraft> drafts = new ArrayList<>();
        borrower.ifPresent(id -> drafts.add(NotificationDraft.of(id, NotificationTemplate.CONTRACT_ACTIVATED, values,
                loanReference(loanId))));
        boolean lendersKnown = lenders.matches(data.allocationVersion());
        if (lendersKnown) {
            lenders.investorIds().forEach(investorId -> drafts.add(NotificationDraft.of(investorId,
                    NotificationTemplate.INVESTOR_CONTRACT_ACTIVATED, values, loanReference(loanId))));
        }
        if (borrower.isEmpty()) return HandlerOutcome.await("BORROWER_UNKNOWN", drafts);
        if (!lendersKnown) return HandlerOutcome.await("LENDERS_UNKNOWN", drafts);
        return HandlerOutcome.deliver(drafts);
    }
}
