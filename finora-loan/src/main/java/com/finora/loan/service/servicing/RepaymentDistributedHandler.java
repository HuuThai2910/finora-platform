package com.finora.loan.service.servicing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.domain.messaging.ProcessedEvent;
import com.finora.loan.domain.servicing.RepaymentEventQuarantine;
import com.finora.loan.messaging.event.RepaymentDistributedEventData;
import com.finora.loan.messaging.event.LoanDelinquencyChangedEventData;
import com.finora.loan.messaging.event.LoanSettledEventData;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.repository.servicing.RepaymentEventQuarantineRepository;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.service.collection.CollectionCaseService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RepaymentDistributedHandler {
    private final FinoraLoanRepository loans;
    private final LoanServicingProjectionRepository projections;
    private final ProcessedEventRepository processed;
    private final OutboxService outbox;
    private final CollectionCaseService collectionCases;
    private final RepaymentEventQuarantineRepository quarantine;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public void handle(UUID eventId, Instant receivedAt, RepaymentDistributedEventData data) {
        if (processed.existsByEventId(eventId)) return;
        var optionalLoan = loans.findByLoanApplicationIdForUpdate(data.loanApplicationId());
        if (optionalLoan.isEmpty()) {
            quarantine.findByEventIdForUpdate(eventId).orElseGet(() -> quarantine.save(
                    RepaymentEventQuarantine.pending(eventId, data.loanApplicationId(), data.repaymentId(),
                            json(data), receivedAt, clock.instant())));
            return;
        }
        var loan = optionalLoan.get();
        if (!loan.getFineractLoanId().equals(data.fineractLoanId())) {
            throw new IllegalArgumentException("Repayment không khớp Fineract loan");
        }
        var projection = projections.findByFinoraLoanIdForUpdate(loan.getId())
                .orElseThrow(() -> new IllegalStateException("Khoản vay thiếu servicing projection"));
        int previousDpd = projection.getDaysPastDue();
        int previousGroup = LoanServicingSyncService.debtGroup(previousDpd);
        projection.applyRepayment(money(data.principalAmount()), money(data.interestAmount()),
                money(data.feeAmount()), money(data.penaltyAmount()), money(data.outstandingPrincipal()),
                money(data.outstandingInterest()), money(data.outstandingFee()),
                money(data.outstandingPenalty()), money(data.totalOutstanding()), money(data.overdueAmount()),
                data.nextDueDate(), money(data.nextDueAmount()), data.completedAt());
        collectionCases.apply(loan, projection, data.completedAt());
        boolean settledNow = money(data.totalOutstanding()).signum() == 0
                && loan.getStatus() != com.finora.loan.domain.servicing.FinoraLoanStatus.SETTLED;
        if (settledNow) {
            loan.settle(data.completedAt());
            outbox.recordForPublication("FinoraLoan", loan.getId().toString(),
                    "LoanSettled", 1, new LoanSettledEventData(
                            loan.getLoanApplicationId(), loan.getLoanNumber(), loan.getContractNumber(),
                            loan.getBorrowerId(), loan.getFineractLoanId(), data.completedAt()));
        }
        int currentDpd = projection.getDaysPastDue();
        if (previousDpd != currentDpd) {
            outbox.recordForPublication("FinoraLoan", loan.getId().toString(),
                    "LoanDelinquencyChanged", 1, new LoanDelinquencyChangedEventData(
                            loan.getLoanApplicationId(), loan.getLoanNumber(), loan.getBorrowerId(),
                            loan.getFineractLoanId(), previousDpd, currentDpd,
                            previousGroup, LoanServicingSyncService.debtGroup(currentDpd),
                            projection.getOverdueAmount().toPlainString(), projection.getOverdueSince(),
                            projection.getTotalOutstanding().toPlainString(), data.completedAt()));
        }
        processed.save(ProcessedEvent.create(eventId, "RepaymentDistributed", 1, "finora-payment",
                data.repaymentId().toString(), receivedAt, clock.instant()));
        quarantine.findByEventIdForUpdate(eventId).ifPresent(value -> value.resolve(clock.instant()));
    }

    @Transactional
    public void replay(UUID eventId) {
        RepaymentEventQuarantine value = quarantine.findByEventIdForUpdate(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy repayment event trong quarantine"));
        if (value.getStatus() == com.finora.loan.domain.servicing.RepaymentEventQuarantineStatus.RESOLVED) return;
        value.attempted(clock.instant());
        try {
            handle(value.getEventId(), value.getReceivedAt(),
                    objectMapper.readValue(value.getPayloadJson(), RepaymentDistributedEventData.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payload quarantine không thể đọc lại", exception);
        }
    }

    private BigDecimal money(String value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : new BigDecimal(value).setScale(2);
    }

    private String json(RepaymentDistributedEventData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Không lưu được RepaymentDistributed hợp lệ vào quarantine", exception);
        }
    }
}
