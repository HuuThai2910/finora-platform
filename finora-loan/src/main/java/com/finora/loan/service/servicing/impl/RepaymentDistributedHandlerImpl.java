package com.finora.loan.service.servicing.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.messaging.entity.ProcessedEvent;
import com.finora.loan.domain.servicing.entity.RepaymentEventQuarantine;
import com.finora.loan.messaging.event.LoanDelinquencyChangedEventData;
import com.finora.loan.messaging.event.LoanSettledEventData;
import com.finora.loan.messaging.event.RepaymentDistributedEventData;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.repository.servicing.RepaymentEventQuarantineRepository;
import com.finora.loan.service.collection.CollectionCaseService;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.servicing.RepaymentDistributedHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Xử lý event {@code RepaymentDistributed} của Payment: cập nhật projection, hồ sơ thu hồi và phát event tất toán/đổi
 * nhóm nợ.
 *
 * <p>Khoản vay chưa có ở Loan thì event được lưu vào quarantine để admin chạy lại sau.</p>
 */
@Service
@RequiredArgsConstructor
public class RepaymentDistributedHandlerImpl implements RepaymentDistributedHandler {
    private final FinoraLoanRepository finoraLoanRepository;
    private final LoanServicingProjectionRepository loanServicingProjectionRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxService outboxService;
    private final CollectionCaseService collectionCaseService;
    private final RepaymentEventQuarantineRepository repaymentEventQuarantineRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    @Transactional
    public void handle(UUID eventId, Instant receivedAt, RepaymentDistributedEventData eventData) {
        if (processedEventRepository.existsByEventId(eventId)) return;
        var optionalLoan = finoraLoanRepository.findByLoanApplicationIdForUpdate(eventData.loanApplicationId());
        if (optionalLoan.isEmpty()) {
            repaymentEventQuarantineRepository.findByEventIdForUpdate(eventId)
                    .orElseGet(() -> repaymentEventQuarantineRepository.save(
                            RepaymentEventQuarantine.pending(eventId, eventData.loanApplicationId(),
                                    eventData.repaymentId(), json(eventData), receivedAt, clock.instant())));
            return;
        }
        var loan = optionalLoan.get();
        if (!loan.getFineractLoanId().equals(eventData.fineractLoanId())) {
            throw new IllegalArgumentException("Repayment không khớp Fineract loan");
        }
        var projection = loanServicingProjectionRepository.findByFinoraLoanIdForUpdate(loan.getId())
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu servicing projection"));
        int previousDpd = projection.getDaysPastDue();
        projection.applyRepayment(money(eventData.principalAmount()), money(eventData.interestAmount()),
                money(eventData.feeAmount()), money(eventData.penaltyAmount()), money(eventData.outstandingPrincipal()),
                money(eventData.outstandingInterest()), money(eventData.outstandingFee()),
                money(eventData.outstandingPenalty()), money(eventData.totalOutstanding()),
                money(eventData.overdueAmount()),
                eventData.nextDueDate(), money(eventData.nextDueAmount()), eventData.completedAt());
        CollectionCaseService.DebtGroupChange debtGroupChange =
                collectionCaseService.apply(loan, projection, previousDpd, eventData.completedAt());
        boolean settledNow = money(eventData.totalOutstanding()).signum() == 0
                && loan.getStatus() != com.finora.loan.domain.servicing.enums.FinoraLoanStatus.SETTLED;
        if (settledNow) {
            loan.settle(eventData.completedAt());
            outboxService.recordForPublication("FinoraLoan", loan.getId().toString(),
                    "LoanSettled", 1, new LoanSettledEventData(
                            loan.getLoanApplicationId(), loan.getLoanNumber(), loan.getContractNumber(),
                            loan.getBorrowerId(), loan.getFineractLoanId(), eventData.completedAt()));
        }
        int currentDpd = projection.getDaysPastDue();
        if (previousDpd != currentDpd) {
            outboxService.recordForPublication("FinoraLoan", loan.getId().toString(),
                    "LoanDelinquencyChanged", 1, new LoanDelinquencyChangedEventData(
                            loan.getLoanApplicationId(), loan.getLoanNumber(), loan.getBorrowerId(),
                            loan.getFineractLoanId(), previousDpd, currentDpd,
                            debtGroupChange.previous(), debtGroupChange.current(),
                            projection.getOverdueAmount().toPlainString(), projection.getOverdueSince(),
                            projection.getTotalOutstanding().toPlainString(), eventData.completedAt()));
        }
        processedEventRepository.save(ProcessedEvent.create(eventId, "RepaymentDistributed", 1, "finora-payment",
                eventData.repaymentId().toString(), receivedAt, clock.instant()));
        repaymentEventQuarantineRepository.findByEventIdForUpdate(eventId)
                .ifPresent(quarantine -> quarantine.resolve(clock.instant()));
    }

    @Override
    @Transactional
    public void replay(UUID eventId) {
        RepaymentEventQuarantine quarantine = repaymentEventQuarantineRepository.findByEventIdForUpdate(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy repayment event trong quarantine"));
        if (quarantine.getStatus() == com.finora.loan.domain.servicing.enums.RepaymentEventQuarantineStatus.RESOLVED) return;
        quarantine.attempted(clock.instant());
        try {
            handle(quarantine.getEventId(), quarantine.getReceivedAt(),
                    objectMapper.readValue(quarantine.getPayloadJson(), RepaymentDistributedEventData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payload quarantine không thể đọc lại", exception);
        }
    }

    private BigDecimal money(String amount) {
        return amount == null ? BigDecimal.ZERO.setScale(2) : new BigDecimal(amount).setScale(2);
    }

    private String json(RepaymentDistributedEventData eventData) {
        try {
            return objectMapper.writeValueAsString(eventData);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Không lưu được RepaymentDistributed hợp lệ vào quarantine", exception);
        }
    }
}
