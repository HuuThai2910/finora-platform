package com.finora.payment.messaging;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
public record KafkaEventEnvelope(UUID eventId,String occurredAt,int version,JsonNode data){}

