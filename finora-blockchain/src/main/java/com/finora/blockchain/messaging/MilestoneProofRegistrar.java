package com.finora.blockchain.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.ProofHashScheme;
import com.finora.blockchain.service.proof.ProofPayloadHasher;
import com.finora.blockchain.service.proof.ProofRegistrationCommand;
import com.finora.blockchain.service.proof.ProofSubmissionService;
import com.finora.blockchain.service.proof.ProofSubmissionView;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Kiểm envelope/header của event nguồn rồi đăng ký proof hash-only. Hash là SHA-256 của {@code data} theo FINORA
 * canonical JSON v2 nên người kiểm toán tính lại được từ đúng event mà service nguồn đã commit vào outbox; Blockchain
 * không lưu {@code data} (không giữ payload tài chính thô). Lỗi contract là {@link IllegalArgumentException} → DLT.
 */
@Component
@RequiredArgsConstructor
public class MilestoneProofRegistrar {

    private final ObjectMapper mapper;
    private final ProofSubmissionService proofs;

    public ProofSubmissionView register(
            ProofMilestone milestone, String payload, String headerEventId, String headerType, String headerVersion
    ) {
        JsonNode envelope = parse(payload, milestone);
        UUID eventId = uuid(envelope.path("eventId").asText(null), milestone);
        JsonNode version = envelope.get("version");
        JsonNode data = envelope.get("data");
        if (version == null || !version.canConvertToExactIntegral() || version.asInt() != milestone.eventVersion()
                || data == null || !data.isObject()
                || (headerEventId != null && !uuid(headerEventId, milestone).equals(eventId))
                || (headerType != null && !milestone.eventType().equals(headerType))
                || (headerVersion != null && !headerVersion.equals(Integer.toString(milestone.eventVersion())))) {
            throw new IllegalArgumentException(milestone.eventType() + " envelope/header không hợp lệ");
        }
        ProofMilestone.Aggregate aggregate = milestone.aggregate(data);
        String hash = ProofPayloadHasher.hash(data, ProofHashScheme.CURRENT, mapper);
        return proofs.register(new ProofRegistrationCommand(
                milestone.sourceService(), eventId, milestone.eventType(), milestone.eventVersion(),
                occurredAt(envelope, milestone), milestone.proofType(), aggregate.type(), aggregate.id(),
                loanApplicationId(data), hash, ProofHashScheme.CURRENT.version()));
    }

    private JsonNode parse(String payload, ProofMilestone milestone) {
        try {
            JsonNode envelope = mapper.readTree(payload);
            if (envelope == null || !envelope.isObject()) {
                throw new IllegalArgumentException(milestone.eventType() + " không phải JSON object");
            }
            return envelope;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(milestone.eventType() + " JSON không hợp lệ", exception);
        }
    }

    private static UUID uuid(String value, ProofMilestone milestone) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException(milestone.eventType() + " eventId không phải UUID", exception);
        }
    }

    private static Instant occurredAt(JsonNode envelope, ProofMilestone milestone) {
        String value = envelope.path("occurredAt").asText(null);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(milestone.eventType() + " occurredAt không phải ISO-8601", exception);
        }
    }

    /** Khoản vay liên quan để explorer gom proof; event v1 của bốn mốc đều có field này. */
    private static Long loanApplicationId(JsonNode data) {
        JsonNode value = data.get("loanApplicationId");
        if (value == null || value.isNull()) {
            return null;
        }
        return ProofMilestone.requirePositiveLong(data, "loanApplicationId");
    }
}
