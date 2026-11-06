package com.finora.blockchain.messaging.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.enums.ProofHashScheme;
import com.finora.blockchain.messaging.event.ProofMilestone;
import com.finora.blockchain.service.proof.ProofRegistrationCommand;
import com.finora.blockchain.service.proof.ProofSubmissionService;
import com.finora.blockchain.service.proof.ProofSubmissionView;
import com.finora.blockchain.util.ProofPayloadHasher;
import com.finora.common.messaging.KafkaEvent;
import com.finora.common.messaging.KafkaEventContract.DataRule;
import com.finora.common.messaging.KafkaEventContract.OccurredAtRule;
import com.finora.common.messaging.KafkaEventContract;
import com.finora.common.messaging.KafkaEventReader;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

/**
 * Kiểm envelope/header của event nguồn rồi đăng ký proof hash-only. Hash là SHA-256 của {@code data} theo FINORA
 * canonical JSON v2 nên người kiểm toán tính lại được từ đúng event mà service nguồn đã commit vào outbox; Blockchain
 * không lưu {@code data} (không giữ payload tài chính thô). Lỗi contract là {@link IllegalArgumentException} → DLT.
 * <p>
 * Kiểm bằng {@link KafkaEventReader} dùng chung: đúng version của mốc, header (nếu có) khớp envelope, {@code data} là
 * JSON object, {@code occurredAt} không bắt buộc (thiếu thì proof có {@code sourceOccurredAt = null}). {@code data} là
 * đúng {@code JsonNode} Jackson đọc từ record nên input của hash không đổi.
 */
@Component
@RequiredArgsConstructor
public class MilestoneProofRegistrar {

    private final ObjectMapper objectMapper;
    private final KafkaEventReader kafkaEventReader;
    private final ProofSubmissionService proofSubmissionService;

    public ProofSubmissionView register(ProofMilestone milestone, ConsumerRecord<String, String> record) {
        KafkaEvent<JsonNode> event = kafkaEventReader.readRaw(record,
                KafkaEventContract.of(milestone.eventType(), milestone.eventVersion())
                        .withDataRule(DataRule.OBJECT).withOccurredAtRule(OccurredAtRule.OPTIONAL));
        JsonNode data = event.data();
        ProofMilestone.Aggregate aggregate = milestone.aggregate(data);
        String hash = ProofPayloadHasher.hash(data, ProofHashScheme.CURRENT, objectMapper);
        return proofSubmissionService.register(new ProofRegistrationCommand(
                milestone.sourceService(), event.eventId(), milestone.eventType(), milestone.eventVersion(),
                event.occurredAt(), milestone.proofType(), aggregate.type(), aggregate.id(),
                loanApplicationId(data), hash, ProofHashScheme.CURRENT.version()));
    }

    /** Khoản vay liên quan để explorer gom proof; event v1 của mọi mốc đều có field này. */
    private static Long loanApplicationId(JsonNode data) {
        JsonNode value = data.get("loanApplicationId");
        if (value == null || value.isNull()) {
            return null;
        }
        return ProofMilestone.requirePositiveLong(data, "loanApplicationId");
    }
}
