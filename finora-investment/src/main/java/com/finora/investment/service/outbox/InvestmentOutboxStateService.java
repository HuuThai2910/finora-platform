package com.finora.investment.service.outbox;

import com.finora.investment.config.InvestmentOutboxProperties;
import com.finora.investment.domain.outbox.InvestmentOutboxEvent;
import com.finora.investment.domain.outbox.InvestmentOutboxEventStatus;
import com.finora.investment.repository.InvestmentOutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InvestmentOutboxStateService {

    private final InvestmentOutboxEventRepository repository;
    private final InvestmentOutboxProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        Instant now = clock.instant();
        return repository.findDueIds(
                InvestmentOutboxEventStatus.PENDING,
                InvestmentOutboxEventStatus.PROCESSING,
                now,
                now.minus(properties.processingLease()),
                PageRequest.of(0, properties.batchSize()));
    }

    @Transactional
    public ClaimedInvestmentOutboxMessage claim(Long id) {
        InvestmentOutboxEvent event = repository.findByIdForUpdate(id).orElse(null);
        if (event == null) {
            return null;
        }
        Instant now = clock.instant();
        Instant leaseExpiredBefore = now.minus(properties.processingLease());
        if (event.getStatus() == InvestmentOutboxEventStatus.PROCESSING
                && event.getProcessingStartedAt() != null
                && !event.getProcessingStartedAt().isAfter(leaseExpiredBefore)
                && event.getAttemptCount() >= properties.maxAttempts()) {
            event.markFailure(
                    "OUTBOX_PROCESSING_LEASE_EXHAUSTED",
                    "Relay mất processing lease quá số lần cho phép",
                    false,
                    properties.maxAttempts(),
                    now,
                    event.getProcessingToken(),
                    now);
            repository.saveAndFlush(event);
            return null;
        }
        UUID token = UUID.randomUUID();
        if (!event.claim(now, leaseExpiredBefore, token)) {
            return null;
        }
        repository.saveAndFlush(event);
        return new ClaimedInvestmentOutboxMessage(event.getId(), token, new InvestmentOutboxMessage(
                event.getEventId(), event.getAggregateType(), event.getAggregateId(),
                event.getEventType(), event.getEventVersion(), event.getPayloadJson(),
                event.getTraceId(), event.getCreatedAt()));
    }

    @Transactional
    public void markPublished(Long id, UUID token) {
        InvestmentOutboxEvent event = repository.findByIdForUpdate(id).orElse(null);
        if (event == null) {
            return;
        }
        event.markPublished(token, clock.instant());
        repository.saveAndFlush(event);
    }

    @Transactional
    public void markFailed(Long id, UUID token, String code, String detail, boolean retryable) {
        InvestmentOutboxEvent event = repository.findByIdForUpdate(id).orElse(null);
        if (event == null) {
            return;
        }
        Instant now = clock.instant();
        event.markFailure(code, detail, retryable, properties.maxAttempts(),
                now.plus(backoff(event.getAttemptCount())), token, now);
        repository.saveAndFlush(event);
    }

    private Duration backoff(int attemptCount) {
        Duration delay = properties.retryBackoff();
        for (int attempt = 1;
             attempt < attemptCount && delay.compareTo(properties.maximumBackoff()) < 0;
             attempt++) {
            try {
                Duration doubled = delay.multipliedBy(2);
                delay = doubled.compareTo(properties.maximumBackoff()) > 0
                        ? properties.maximumBackoff() : doubled;
            } catch (ArithmeticException overflow) {
                return properties.maximumBackoff();
            }
        }
        return delay;
    }
}
