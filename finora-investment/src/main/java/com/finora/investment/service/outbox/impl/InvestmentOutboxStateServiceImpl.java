package com.finora.investment.service.outbox.impl;

import com.finora.investment.config.InvestmentOutboxProperties;
import com.finora.investment.domain.outbox.entity.InvestmentOutboxEvent;
import com.finora.investment.domain.outbox.enums.InvestmentOutboxEventStatus;
import com.finora.investment.repository.InvestmentOutboxEventRepository;
import com.finora.investment.service.outbox.ClaimedInvestmentOutboxMessage;
import com.finora.investment.service.outbox.InvestmentOutboxMessage;
import com.finora.investment.service.outbox.InvestmentOutboxStateService;
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
 * Trạng thái event outbox: lấy event đến hạn, nhận event (khoá + lease), ghi đã gửi hoặc lỗi.
 * <p>
 * Event PROCESSING quá {@code processingLease} được nhận lại; mất lease quá {@code maxAttempts} lần thì ghi lỗi
 * không thử lại.
 */
@Service
@RequiredArgsConstructor
public class InvestmentOutboxStateServiceImpl implements InvestmentOutboxStateService {

    private final InvestmentOutboxEventRepository investmentOutboxEventRepository;
    private final InvestmentOutboxProperties investmentOutboxProperties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        Instant now = clock.instant();
        return investmentOutboxEventRepository.findDueIds(
                InvestmentOutboxEventStatus.PENDING,
                InvestmentOutboxEventStatus.PROCESSING,
                now,
                now.minus(investmentOutboxProperties.processingLease()),
                PageRequest.of(0, investmentOutboxProperties.batchSize()));
    }

    @Override
    @Transactional
    public ClaimedInvestmentOutboxMessage claim(Long id) {
        InvestmentOutboxEvent event = investmentOutboxEventRepository.findByIdForUpdate(id).orElse(null);
        if (event == null) {
            return null;
        }
        Instant now = clock.instant();
        Instant leaseExpiredBefore = now.minus(investmentOutboxProperties.processingLease());
        if (event.getStatus() == InvestmentOutboxEventStatus.PROCESSING
                && event.getProcessingStartedAt() != null
                && !event.getProcessingStartedAt().isAfter(leaseExpiredBefore)
                && event.getAttemptCount() >= investmentOutboxProperties.maxAttempts()) {
            event.markFailure(
                    "OUTBOX_PROCESSING_LEASE_EXHAUSTED",
                    "Relay mất processing lease quá số lần cho phép",
                    false,
                    investmentOutboxProperties.maxAttempts(),
                    now,
                    event.getProcessingToken(),
                    now);
            investmentOutboxEventRepository.saveAndFlush(event);
            return null;
        }
        UUID token = UUID.randomUUID();
        if (!event.claim(now, leaseExpiredBefore, token)) {
            return null;
        }
        investmentOutboxEventRepository.saveAndFlush(event);
        return new ClaimedInvestmentOutboxMessage(event.getId(), token, new InvestmentOutboxMessage(
                event.getEventId(), event.getAggregateType(), event.getAggregateId(),
                event.getEventType(), event.getEventVersion(), event.getPayloadJson(),
                event.getTraceId(), event.getCreatedAt()));
    }

    @Override
    @Transactional
    public void markPublished(Long id, UUID token) {
        InvestmentOutboxEvent event = investmentOutboxEventRepository.findByIdForUpdate(id).orElse(null);
        if (event == null) {
            return;
        }
        event.markPublished(token, clock.instant());
        investmentOutboxEventRepository.saveAndFlush(event);
    }

    @Override
    @Transactional
    public void markFailed(Long id, UUID token, String code, String detail, boolean retryable) {
        InvestmentOutboxEvent event = investmentOutboxEventRepository.findByIdForUpdate(id).orElse(null);
        if (event == null) {
            return;
        }
        Instant now = clock.instant();
        event.markFailure(code, detail, retryable, investmentOutboxProperties.maxAttempts(),
                now.plus(backoff(event.getAttemptCount())), token, now);
        investmentOutboxEventRepository.saveAndFlush(event);
    }

    private Duration backoff(int attemptCount) {
        Duration delay = investmentOutboxProperties.retryBackoff();
        for (int attempt = 1;
             attempt < attemptCount && delay.compareTo(investmentOutboxProperties.maximumBackoff()) < 0;
             attempt++) {
            try {
                Duration doubled = delay.multipliedBy(2);
                delay = doubled.compareTo(investmentOutboxProperties.maximumBackoff()) > 0
                        ? investmentOutboxProperties.maximumBackoff() : doubled;
            } catch (ArithmeticException overflow) {
                return investmentOutboxProperties.maximumBackoff();
            }
        }
        return delay;
    }
}
