package com.finora.blockchain.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.ProofType;
import com.finora.blockchain.service.proof.ProofRegistrationCommand;
import com.finora.blockchain.service.proof.ProofSubmissionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** Chỉ neo hash của kết quả repayment; payload tài chính gốc tiếp tục thuộc Payment. */
@Component
@RequiredArgsConstructor
public class RepaymentDistributedProofConsumer {
    private final ObjectMapper mapper;
    private final ProofSubmissionService proofs;

    @KafkaListener(topics = "${finora.blockchain.repayment-distributed-topic:finora.payment.repayment-distributed}")
    public void consume(@Payload String payload,
            @Header(name = "finora-event-id", required = false) String id,
            @Header(name = "finora-event-type", required = false) String type,
            @Header(name = "finora-event-version", required = false) String version) {
        try {
            JsonNode envelope = mapper.readTree(payload);
            UUID eventId = UUID.fromString(envelope.path("eventId").asText());
            int eventVersion = envelope.path("version").asInt();
            JsonNode data = envelope.path("data");
            String repaymentId = data.path("repaymentId").asText();
            if (eventVersion != 1 || repaymentId.isBlank() || data.isMissingNode()
                    || (id != null && !UUID.fromString(id).equals(eventId))
                    || (type != null && !"RepaymentDistributed".equals(type))
                    || (version != null && Integer.parseInt(version) != 1)) {
                throw new IllegalArgumentException("RepaymentDistributed envelope/header không hợp lệ");
            }
            byte[] canonical = mapper.writeValueAsBytes(data);
            proofs.register(new ProofRegistrationCommand("finora-payment", eventId, "Repayment",
                    repaymentId, ProofType.REPAYMENT, sha256(canonical), eventVersion));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("RepaymentDistributed JSON không hợp lệ", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Runtime thiếu SHA-256", exception);
        }
    }
}
