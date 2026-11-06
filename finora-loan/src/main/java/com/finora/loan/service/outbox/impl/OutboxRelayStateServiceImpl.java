package com.finora.loan.service.outbox.impl;

import com.finora.loan.config.OutboxProperties;
import com.finora.loan.domain.outbox.entity.OutboxEvent;
import com.finora.loan.domain.outbox.enums.OutboxEventStatus;
import com.finora.loan.repository.outbox.OutboxEventRepository;
import com.finora.loan.service.outbox.ClaimedOutboxMessage;
import com.finora.loan.service.outbox.OutboxMessage;
import com.finora.loan.service.outbox.OutboxRelayStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mỗi thay đổi trạng thái relay là một transaction ngắn, tách khỏi thao tác broker.
 * <p>
 * Claim bằng token và processing lease để relay cũ không xác nhận nhầm event đã bị instance khác tiếp quản; lỗi retry
 * được thì hẹn lần sau theo backoff nhân đôi, tối đa {@code maximumBackoff}.
 */
@Service
@RequiredArgsConstructor
public class OutboxRelayStateServiceImpl implements OutboxRelayStateService {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxProperties outboxProperties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        Instant now = clock.instant();
        return outboxEventRepository.findDueIds(
                OutboxEventStatus.PENDING,
                OutboxEventStatus.PROCESSING,
                now,
                now.minus(outboxProperties.processingLease()),
                PageRequest.of(0, outboxProperties.batchSize())
        );
    }

    @Override
    @Transactional
    public ClaimedOutboxMessage claim(Long id) {
        OutboxEvent event = outboxEventRepository.findByIdForUpdate(id).orElse(null);
        if (event == null) {
            return null;
        }
        Instant now = clock.instant();
        Instant leaseExpiredBefore = now.minus(outboxProperties.processingLease());
        if (event.getStatus() == OutboxEventStatus.PROCESSING
                && event.getProcessingStartedAt() != null
                && !event.getProcessingStartedAt().isAfter(leaseExpiredBefore)
                && event.getAttemptCount() >= outboxProperties.maxAttempts()) {
            event.markPublishFailure(
                    "OUTBOX_PROCESSING_LEASE_EXHAUSTED",
                    "Relay đã mất processing lease quá số lần cho phép",
                    false,
                    outboxProperties.maxAttempts(),
                    now,
                    event.getProcessingToken(),
                    now
            );
            outboxEventRepository.saveAndFlush(event);
            return null;
        }
        UUID claimToken = UUID.randomUUID();
        if (!event.claim(now, leaseExpiredBefore, claimToken)) {
            return null;
        }
        outboxEventRepository.saveAndFlush(event);
        return new ClaimedOutboxMessage(event.getId(), claimToken, new OutboxMessage(
                event.getEventId(), event.getAggregateType(), event.getAggregateId(),
                event.getEventType(), event.getEventVersion(), event.getPayloadJson(),
                event.getTraceId(), event.getCreatedAt()
        ));
    }

    @Override
    @Transactional
    public void markPublished(Long id, UUID claimToken) {
        OutboxEvent event = outboxEventRepository.findByIdForUpdate(id).orElse(null);
        if (event == null) {
            return;
        }
        event.markPublished(claimToken, clock.instant());
        outboxEventRepository.saveAndFlush(event);
    }

    @Override
    @Transactional
    public void markFailed(
            Long id,
            UUID claimToken,
            String errorCode,
            String errorDetail,
            boolean retryable
    ) {
        OutboxEvent event = outboxEventRepository.findByIdForUpdate(id).orElse(null);
        if (event == null) {
            return;
        }
        Instant now = clock.instant();
        event.markPublishFailure(
                errorCode,
                errorDetail,
                retryable,
                outboxProperties.maxAttempts(),
                now.plus(backoff(event.getAttemptCount())),
                claimToken,
                now
        );
        outboxEventRepository.saveAndFlush(event);
    }

    private Duration backoff(int attemptCount) {
        Duration delay = outboxProperties.retryBackoff();
        for (int attempt = 1;
                attempt < attemptCount && delay.compareTo(outboxProperties.maximumBackoff()) < 0;
                attempt++) {
            Duration doubled;
            try {
                doubled = delay.multipliedBy(2);
            } catch (ArithmeticException overflow) {
                return outboxProperties.maximumBackoff();
            }
            delay = doubled.compareTo(outboxProperties.maximumBackoff()) > 0
                    ? outboxProperties.maximumBackoff()
                    : doubled;
        }
        return delay;
    }
}
