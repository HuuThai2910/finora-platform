package com.finora.loan.service.outbox;

import com.finora.common.logging.TraceContext;
import com.finora.loan.domain.outbox.OutboxEvent;
import com.finora.loan.repository.outbox.OutboxEventRepository;
import com.finora.loan.support.HashingService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ghi domain event cùng local transaction với aggregate; không publish mạng tại đây. */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository repository;
    private final HashingService hashingService;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(
            String aggregateType,
            String aggregateId,
            String eventType,
            int eventVersion,
            Object payload
    ) {
        Instant now = clock.instant();
        UUID eventId = UUID.randomUUID();
        repository.save(OutboxEvent.create(
                eventId,
                aggregateType,
                aggregateId,
                eventType,
                eventVersion,
                hashingService.toJson(payload),
                TraceContext.currentTraceIdOrCreate(),
                now
        ));
        return eventId;
    }
}
