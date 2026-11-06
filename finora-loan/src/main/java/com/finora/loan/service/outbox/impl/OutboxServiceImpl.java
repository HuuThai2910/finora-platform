package com.finora.loan.service.outbox.impl;

import com.finora.common.logging.TraceContext;
import com.finora.loan.domain.outbox.entity.OutboxEvent;
import com.finora.loan.repository.outbox.OutboxEventRepository;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.util.HashingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Ghi domain event cùng local transaction với aggregate; không publish mạng tại đây.
 * <p>
 * Payload được serialize bằng {@link HashingService#toJson(Object)} và gắn trace ID hiện tại; relay đọc bảng outbox để
 * đẩy event được phép publish ra Kafka sau khi transaction commit.
 */
@Service
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final HashingService hashingService;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            Object payload
    ) {
        return record(aggregateType, aggregateId, eventType, eventVersion, payload, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID recordForPublication(
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            Object payload
    ) {
        return record(aggregateType, aggregateId, eventType, eventVersion, payload, true);
    }

    private UUID record(
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            Object payload,
            boolean publishable
    ) {
        Instant now = clock.instant();
        UUID eventId = UUID.randomUUID();
        outboxEventRepository.save(OutboxEvent.create(
                eventId,
                aggregateType,
                aggregateId,
                eventType,
                eventVersion,
                hashingService.toJson(payload),
                publishable,
                TraceContext.currentTraceIdOrCreate(),
                now
        ));
        return eventId;
    }
}
