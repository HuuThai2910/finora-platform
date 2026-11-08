package com.finora.loan.service.servicing.impl;

import com.finora.loan.domain.messaging.entity.ProcessedEvent;
import com.finora.loan.domain.servicing.entity.PendingScheduledRepayment;
import com.finora.loan.messaging.event.RepaymentCancelledEventData;
import com.finora.loan.messaging.event.RepaymentCollectedEventData;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.repository.servicing.PendingScheduledRepaymentRepository;
import com.finora.loan.service.servicing.PendingScheduledRepaymentHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PendingScheduledRepaymentHandlerImpl implements PendingScheduledRepaymentHandler {
    private final ProcessedEventRepository processedEventRepository;
    private final PendingScheduledRepaymentRepository pendingRepository;
    private final Clock clock;

    @Override
    @Transactional
    public void collected(UUID eventId, Instant occurredAt, RepaymentCollectedEventData data) {
        if (processedEventRepository.existsByEventId(eventId)) return;
        if (data == null || data.repaymentId() == null || data.loanApplicationId() == null
                || data.borrowerId() == null || data.dueDate() == null || data.collectedAt() == null) {
            throw new IllegalArgumentException("RepaymentCollected thiếu dữ liệu");
        }
        Instant now = clock.instant();
        pendingRepository.save(PendingScheduledRepayment.collected(data.loanApplicationId(), data.repaymentId(),
                data.borrowerId(), data.dueDate(), data.collectedAt(), now));
        processedEventRepository.save(ProcessedEvent.create(eventId, "RepaymentCollected", 1, "finora-payment",
                data.repaymentId().toString(), occurredAt, now));
    }

    @Override
    @Transactional
    public void cancelled(UUID eventId, Instant occurredAt, RepaymentCancelledEventData data) {
        if (processedEventRepository.existsByEventId(eventId)) return;
        if (data == null || data.repaymentId() == null || data.loanApplicationId() == null
                || data.cancelledAt() == null) throw new IllegalArgumentException("RepaymentCancelled thiếu dữ liệu");
        Instant now = clock.instant();
        pendingRepository.deleteByLoanApplicationIdAndRepaymentId(data.loanApplicationId(), data.repaymentId());
        processedEventRepository.save(ProcessedEvent.create(eventId, "RepaymentCancelled", 1, "finora-payment",
                data.repaymentId().toString(), occurredAt, now));
    }
}
