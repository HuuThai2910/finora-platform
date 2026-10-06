package com.finora.payment.service.servicing;

import com.finora.payment.domain.disbursement.PaymentDisbursement;
import com.finora.payment.domain.messaging.PaymentProcessedEvent;
import com.finora.payment.domain.servicing.PaymentLoanAccount;
import com.finora.payment.domain.servicing.PaymentNoteOwnership;
import com.finora.payment.messaging.LoanDisbursedEventData;
import com.finora.payment.messaging.NoteOwnershipChangedEventData;
import com.finora.payment.repository.PaymentDisbursementRepository;
import com.finora.payment.repository.PaymentProcessedEventRepository;
import com.finora.payment.repository.servicing.PaymentLoanAccountRepository;
import com.finora.payment.repository.servicing.PaymentNoteOwnershipRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentServicingProjectionService {
    private final PaymentLoanAccountRepository loanAccounts;
    private final PaymentNoteOwnershipRepository noteOwnership;
    private final PaymentDisbursementRepository disbursements;
    private final PaymentProcessedEventRepository processedEvents;
    private final Clock clock;

    @Transactional
    public void activateLoan(UUID eventId, LoanDisbursedEventData data) {
        if (processedEvents.existsByEventId(eventId)) return;
        PaymentDisbursement disbursement = disbursements.findBySagaId(data.sagaId())
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy kết quả giải ngân trong Payment"));
        if (!disbursement.getLoanApplicationId().equals(data.loanApplicationId())
                || !disbursement.getContractNumber().equals(data.contractNumber())) {
            throw new IllegalArgumentException("LoanDisbursed không khớp giao dịch giải ngân Payment");
        }
        PaymentLoanAccount existing = loanAccounts.findByLoanApplicationId(data.loanApplicationId()).orElse(null);
        if (existing == null) {
            loanAccounts.save(PaymentLoanAccount.activate(data.loanApplicationId(), data.contractNumber(),
                    data.listingId(), disbursement.getBorrowerId(), data.fineractLoanId(),
                    data.coreConfigVersion(), data.currency(),
                    data.disbursedAt()));
        } else if (!existing.getFineractLoanId().equals(data.fineractLoanId())) {
            throw new IllegalArgumentException("Khoản vay Payment đã gắn Fineract loan khác");
        }
        processedEvents.save(PaymentProcessedEvent.of(eventId, "LoanDisbursed",
                data.loanApplicationId().toString(), clock.instant()));
    }

    @Transactional
    public void updateOwnership(UUID eventId, NoteOwnershipChangedEventData data) {
        if (processedEvents.existsByEventId(eventId)) return;
        if (data.notes() == null || data.notes().isEmpty()) {
            throw new IllegalArgumentException("NoteOwnershipChanged phải có ít nhất một Note");
        }
        var now = clock.instant();
        for (NoteOwnershipChangedEventData.NoteOwner change : data.notes()) {
            BigDecimal outstanding = new BigDecimal(change.outstandingPrincipal()).setScale(2);
            PaymentNoteOwnership current = noteOwnership.findByNoteIdForUpdate(change.noteId()).orElse(null);
            if (current == null) {
                noteOwnership.save(PaymentNoteOwnership.create(change.noteId(), change.noteNumber(),
                        data.loanApplicationId(), data.listingId(), change.investorId(), outstanding,
                        data.currency(), data.changedAt(), data.reference(), now));
            } else {
                if (!current.getLoanApplicationId().equals(data.loanApplicationId())) {
                    throw new IllegalArgumentException("Note không thuộc khoản vay trong event");
                }
                current.apply(change.investorId(), outstanding, data.currency(), data.changedAt(),
                        data.reference(), now);
            }
        }
        processedEvents.save(PaymentProcessedEvent.of(eventId, "NoteOwnershipChanged",
                data.loanApplicationId().toString(), now));
    }
}
