package com.finora.investment.service.outbox.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.logging.TraceContext;
import com.finora.investment.domain.outbox.InvestmentOutboxEvent;
import com.finora.investment.repository.InvestmentOutboxEventRepository;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvestmentOutboxServiceImpl implements InvestmentOutboxService {

    private final InvestmentOutboxEventRepository repository;
    private final ObjectMapper objectMapper;
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
        Instant now = clock.instant();
        UUID eventId = UUID.randomUUID();
        repository.save(InvestmentOutboxEvent.create(
                eventId, aggregateType, aggregateId, eventType, eventVersion,
                toJson(payload), TraceContext.currentTraceIdOrCreate(), now));
        return eventId;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Không thể serialize Investment event", exception);
        }
    }
}
