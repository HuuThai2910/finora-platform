package com.finora.notification.messaging.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** Envelope chung {@code {eventId, occurredAt, version, data}} của Loan, Payment và Investment. */
public record KafkaEnvelope(UUID eventId, String occurredAt, int version, JsonNode data) {}
