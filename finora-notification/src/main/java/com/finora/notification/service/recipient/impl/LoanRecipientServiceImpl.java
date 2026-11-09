package com.finora.notification.service.recipient.impl;

import com.finora.notification.domain.entity.LoanLender;
import com.finora.notification.domain.entity.LoanReference;
import com.finora.notification.exception.RecipientConflictException;
import com.finora.notification.repository.LoanLenderRepository;
import com.finora.notification.repository.LoanReferenceRepository;
import com.finora.notification.service.recipient.LoanRecipientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bảng tra người nhận theo khoản vay (D60, B64). Ghi trong transaction của intake: cùng commit với thông báo dùng nó.
 * Event mang người vay khác với dữ liệu đã có thì ném {@link RecipientConflictException} (vào DLT): gửi nhầm người là lộ
 * thông tin khoản vay, nên không bao giờ tự chọn một bên.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanRecipientServiceImpl implements LoanRecipientService {

    private final LoanReferenceRepository loanReferenceRepository;
    private final LoanLenderRepository loanLenderRepository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LoanReference remember(Long loanApplicationId, String borrowerId, String applicationNumber,
            String contractNumber, String loanNumber, UUID sourceEventId) {
        requireLoanId(loanApplicationId);
        Instant now = clock.instant();
        LoanReference reference = loanReferenceRepository.findForUpdate(loanApplicationId)
                .orElseGet(() -> loanReferenceRepository.save(
                        LoanReference.create(loanApplicationId, sourceEventId, now)));
        if (!reference.acceptBorrower(borrowerId)) {
            log.error("Người vay trong event khác bảng tra: loanApplicationId={}, eventId={}",
                    loanApplicationId, sourceEventId);
            throw new RecipientConflictException("Người vay của khoản vay " + loanApplicationId
                    + " không khớp giữa các event");
        }
        reference.fillLabels(applicationNumber, contractNumber, loanNumber);
        reference.touched(sourceEventId, now);
        return reference;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LoanReference> find(Long loanApplicationId) {
        return loanReferenceRepository.findById(loanApplicationId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void rememberDisplayCodes(Long loanApplicationId, String applicationCode, String contractCode) {
        if ((applicationCode == null || applicationCode.isBlank()) && (contractCode == null || contractCode.isBlank())) {
            return;
        }
        loanReferenceRepository.findForUpdate(loanApplicationId)
                .ifPresent(reference -> reference.fillDisplayCodes(applicationCode, contractCode));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> borrower(Long loanApplicationId, String borrowerFromEvent, String applicationNumber,
            String contractNumber, String loanNumber, UUID sourceEventId) {
        if (borrowerFromEvent != null && !borrowerFromEvent.isBlank()) {
            return Optional.ofNullable(remember(loanApplicationId, borrowerFromEvent, applicationNumber,
                    contractNumber, loanNumber, sourceEventId).getBorrowerId());
        }
        Optional<LoanReference> reference = loanReferenceRepository.findForUpdate(loanApplicationId);
        reference.ifPresent(value -> {
            value.fillLabels(applicationNumber, contractNumber, loanNumber);
            value.touched(sourceEventId, clock.instant());
        });
        return reference.map(LoanReference::getBorrowerId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceLenders(Long loanApplicationId, long allocationVersion, Collection<String> investorIds,
            String applicationNumber, UUID sourceEventId) {
        LoanReference reference = remember(loanApplicationId, null, applicationNumber, null, null, sourceEventId);
        Long current = reference.getLenderAllocationVersion();
        if (current != null && current >= allocationVersion) return;
        Instant now = clock.instant();
        loanLenderRepository.deleteByLoan(loanApplicationId);
        List<LoanLender> rows = new LinkedHashSet<>(investorIds).stream()
                .filter(id -> id != null && !id.isBlank())
                .map(id -> LoanLender.create(loanApplicationId, id, allocationVersion, now))
                .toList();
        loanLenderRepository.saveAll(rows);
        reference.lendersReplaced(allocationVersion);
        reference.touched(sourceEventId, now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Lenders lenders(Long loanApplicationId) {
        Long version = loanReferenceRepository.findById(loanApplicationId)
                .map(LoanReference::getLenderAllocationVersion)
                .orElse(null);
        List<String> ids = loanLenderRepository.findByLoanApplicationIdOrderByInvestorId(loanApplicationId).stream()
                .map(LoanLender::getInvestorId).toList();
        return new Lenders(version, ids);
    }

    private static void requireLoanId(Long loanApplicationId) {
        if (loanApplicationId == null || loanApplicationId <= 0) {
            throw new IllegalArgumentException("Event thiếu loanApplicationId");
        }
    }
}
