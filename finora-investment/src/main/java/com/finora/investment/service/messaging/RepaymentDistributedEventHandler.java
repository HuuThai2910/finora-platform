package com.finora.investment.service.messaging;

import com.finora.investment.domain.messaging.ProcessedEvent;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.messaging.event.RepaymentDistributedEventData;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import com.finora.investment.messaging.event.InvestorServicingNotificationEventData;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RepaymentDistributedEventHandler {
    private final InvestmentNoteRepository notes;
    private final ProcessedEventRepository processed;
    private final InvestmentOutboxService outbox;
    private final Clock clock;

    @Transactional
    public void handle(UUID eventId, Instant occurredAt, RepaymentDistributedEventData data) {
        if (processed.existsByEventId(eventId)) return;
        BigDecimal principalTotal = BigDecimal.ZERO.setScale(2);
        BigDecimal interestTotal = BigDecimal.ZERO.setScale(2);
        for (RepaymentDistributedEventData.NoteAllocation allocation : data.allocations()) {
            var note = notes.findByIdForUpdate(allocation.noteId()).orElseThrow(() ->
                    InvestmentDomainException.notFound("NOTE_NOT_FOUND", "Không tìm thấy Note trong phân bổ repayment"));
            if (!note.getLoanId().equals(data.loanApplicationId())
                    || !note.getNoteNumber().equals(allocation.noteNumber())) {
                throw InvestmentDomainException.conflict("REPAYMENT_NOTE_MISMATCH", "Note không khớp khoản vay repayment");
            }
            BigDecimal principal = new BigDecimal(allocation.principalAmount()).setScale(2);
            BigDecimal interest = new BigDecimal(allocation.interestAmount()).setScale(2);
            note.applyRepayment(principal, interest, data.completedAt());
            principalTotal = principalTotal.add(principal);
            interestTotal = interestTotal.add(interest);
        }
        if (principalTotal.compareTo(new BigDecimal(data.principalAmount())) != 0
                || interestTotal.compareTo(new BigDecimal(data.interestAmount())) != 0) {
            throw InvestmentDomainException.conflict("REPAYMENT_ALLOCATION_UNBALANCED",
                    "Tổng phân bổ Note không khớp breakdown Payment/Fineract");
        }
        var recipients = data.allocations().stream()
                .map(item -> new InvestorServicingNotificationEventData.Recipient(
                        item.noteId(), item.noteNumber(), item.investorId()))
                .distinct().toList();
        if (!recipients.isEmpty()) {
            String changeType = "EARLY_SETTLEMENT".equals(data.repaymentType())
                    ? "EARLY_SETTLEMENT" : "REPAYMENT_CREDITED";
            outbox.record("LOAN", data.loanApplicationId().toString(),
                    "InvestorNoteServicingChanged", 1,
                    new InvestorServicingNotificationEventData(changeType,
                            data.loanApplicationId(), null, 0, 1, data.amount(),
                            null, "EARLY_SETTLEMENT".equals(data.repaymentType()),
                            data.completedAt(), recipients));
        }
        processed.save(ProcessedEvent.create(eventId, "RepaymentDistributed", 1, "finora-payment",
                data.repaymentId().toString(), occurredAt, clock.instant()));
    }
}
