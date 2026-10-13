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
import com.finora.investment.domain.secondary.NoteRepaymentRedirect;
import com.finora.investment.repository.NoteRepaymentRedirectRepository;
import com.finora.investment.repository.NoteTransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepaymentDistributedEventHandler {
    private final InvestmentNoteRepository notes;
    private final ProcessedEventRepository processed;
    private final InvestmentOutboxService outbox;
    private final NoteTransferRepository transfers;
    private final NoteRepaymentRedirectRepository redirects;
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
            if (!note.getInvestorId().equals(allocation.investorId())) {
                redirectToCurrentOwner(data.repaymentId(), note.getId(), allocation.investorId(),
                        note.getInvestorId(), principal.add(interest));
            }
            principalTotal = principalTotal.add(principal);
            interestTotal = interestTotal.add(interest);
        }
        if (principalTotal.compareTo(new BigDecimal(data.principalAmount())) != 0
                || interestTotal.compareTo(new BigDecimal(data.interestAmount())) != 0) {
            throw InvestmentDomainException.conflict("REPAYMENT_ALLOCATION_UNBALANCED",
                    "Tổng phân bổ Note không khớp breakdown Payment/Fineract");
        }
        // Người nhận thông báo là chủ hiện tại theo Investment, không phải người Payment đã trả:
        // khoản trả nhầm chủ cũ sẽ được worker chuyển lại cho họ.
        var currentOwners = notes.findAllById(data.allocations().stream()
                        .map(RepaymentDistributedEventData.NoteAllocation::noteId).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.finora.investment.domain.note.InvestmentNote::getId,
                        com.finora.investment.domain.note.InvestmentNote::getInvestorId));
        var recipients = data.allocations().stream()
                .map(item -> new InvestorServicingNotificationEventData.Recipient(
                        item.noteId(), item.noteNumber(), currentOwners.getOrDefault(item.noteId(), item.investorId()),
                        new BigDecimal(item.principalAmount()).add(new BigDecimal(item.interestAmount()))
                                .setScale(2).toPlainString()))
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

    /**
     * Payment chia tiền cho người không còn là chủ Note: chỉ chấp nhận khi người đó đúng là từng bán
     * Note này (Payment chưa nhận {@code NoteOwnershipChanged}); khác đi là dữ liệu mâu thuẫn thật,
     * ném lỗi để event vào DLT. Ghi khoản phải thu cùng transaction, worker chuyển tiền sau.
     *
     * <p>Quy tắc: lần trả nợ thuộc về chủ Note tại lúc Investment ghi nhận nó. Giá khớp tính trên dư
     * nợ Investment đang có — chưa trừ lần trả này — nên người mua đã trả tiền cho đúng phần gốc
     * đó; để người bán nhận nữa là người bán được hai lần.</p>
     */
    private void redirectToCurrentOwner(java.util.UUID repaymentId, Long noteId, String paidTo,
            String currentOwner, BigDecimal amount) {
        if (!transfers.existsByNoteIdAndSellerId(noteId, paidTo)) {
            throw InvestmentDomainException.conflict("REPAYMENT_OWNER_MISMATCH",
                    "Payment chia tiền Note cho người không phải chủ và chưa từng bán Note này");
        }
        if (amount.signum() <= 0 || redirects.existsByRepaymentIdAndNoteId(repaymentId, noteId)) {
            return;
        }
        redirects.save(NoteRepaymentRedirect.pending(
                repaymentId, noteId, paidTo, currentOwner, amount, clock.instant()));
        log.warn("Repayment chia cho chủ cũ của Note, ghi khoản chuyển lại: repaymentId={}, noteId={}",
                repaymentId, noteId);
    }
}
