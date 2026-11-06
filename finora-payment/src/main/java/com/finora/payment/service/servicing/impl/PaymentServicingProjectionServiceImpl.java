package com.finora.payment.service.servicing.impl;

import com.finora.payment.domain.disbursement.entity.PaymentDisbursement;
import com.finora.payment.domain.messaging.entity.PaymentProcessedEvent;
import com.finora.payment.domain.servicing.entity.PaymentLoanAccount;
import com.finora.payment.domain.servicing.entity.PaymentNoteOwnership;
import com.finora.payment.messaging.event.LoanDisbursedEventData;
import com.finora.payment.messaging.event.NoteOwnershipChangedEventData;
import com.finora.payment.repository.PaymentDisbursementRepository;
import com.finora.payment.repository.PaymentProcessedEventRepository;
import com.finora.payment.repository.servicing.PaymentLoanAccountRepository;
import com.finora.payment.repository.servicing.PaymentNoteOwnershipRepository;
import com.finora.payment.service.servicing.PaymentServicingProjectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

/**
 * Bản chiếu dữ liệu thu nợ trong Payment theo event của Loan và Investment.
 * <p>
 * Event đã xử lý được ghi vào {@code payment_processed_events} cùng transaction với thay đổi, nên event gửi lại không
 * có tác dụng lần hai. {@code LoanDisbursed} phải khớp giao dịch giải ngân của Payment theo sagaId; Note đã có thì khóa
 * dòng rồi cập nhật.
 */
@Service
@RequiredArgsConstructor
public class PaymentServicingProjectionServiceImpl implements PaymentServicingProjectionService {
    private final PaymentLoanAccountRepository paymentLoanAccountRepository;
    private final PaymentNoteOwnershipRepository paymentNoteOwnershipRepository;
    private final PaymentDisbursementRepository paymentDisbursementRepository;
    private final PaymentProcessedEventRepository paymentProcessedEventRepository;
    private final Clock clock;

    @Override
    @Transactional
    public void activateLoan(UUID eventId, LoanDisbursedEventData data) {
        if (paymentProcessedEventRepository.existsByEventId(eventId)) return;
        PaymentDisbursement disbursement = paymentDisbursementRepository.findBySagaId(data.sagaId())
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy kết quả giải ngân trong Payment"));
        if (!disbursement.getLoanApplicationId().equals(data.loanApplicationId())
                || !disbursement.getContractNumber().equals(data.contractNumber())) {
            throw new IllegalArgumentException("LoanDisbursed không khớp giao dịch giải ngân Payment");
        }
        PaymentLoanAccount existing = paymentLoanAccountRepository.findByLoanApplicationId(data.loanApplicationId())
                .orElse(null);
        if (existing == null) {
            paymentLoanAccountRepository.save(PaymentLoanAccount.activate(data.loanApplicationId(),
                    data.contractNumber(), data.listingId(), disbursement.getBorrowerId(), data.fineractLoanId(),
                    data.coreConfigVersion(), data.earlySettlementPolicyVersion(), data.currency(),
                    data.disbursedAt()));
        } else if (!existing.getFineractLoanId().equals(data.fineractLoanId())) {
            throw new IllegalArgumentException("Khoản vay Payment đã gắn Fineract loan khác");
        }
        paymentProcessedEventRepository.save(PaymentProcessedEvent.of(eventId, "LoanDisbursed",
                data.loanApplicationId().toString(), clock.instant()));
    }

    @Override
    @Transactional
    public void updateOwnership(UUID eventId, NoteOwnershipChangedEventData data) {
        if (paymentProcessedEventRepository.existsByEventId(eventId)) return;
        if (data.notes() == null || data.notes().isEmpty()) {
            throw new IllegalArgumentException("NoteOwnershipChanged phải có ít nhất một Note");
        }
        var now = clock.instant();
        for (NoteOwnershipChangedEventData.NoteOwner change : data.notes()) {
            BigDecimal outstanding = new BigDecimal(change.outstandingPrincipal()).setScale(2);
            PaymentNoteOwnership current = paymentNoteOwnershipRepository.findByNoteIdForUpdate(change.noteId())
                    .orElse(null);
            if (current == null) {
                paymentNoteOwnershipRepository.save(PaymentNoteOwnership.create(change.noteId(), change.noteNumber(),
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
        paymentProcessedEventRepository.save(PaymentProcessedEvent.of(eventId, "NoteOwnershipChanged",
                data.loanApplicationId().toString(), now));
    }
}
