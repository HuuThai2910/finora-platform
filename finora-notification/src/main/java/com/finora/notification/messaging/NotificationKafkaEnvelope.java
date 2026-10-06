package com.finora.notification.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record NotificationKafkaEnvelope(UUID eventId, String occurredAt, int version, JsonNode data) {}
