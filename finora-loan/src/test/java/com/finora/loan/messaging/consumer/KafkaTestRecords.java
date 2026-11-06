package com.finora.loan.messaging.consumer;

import com.finora.common.messaging.FinoraKafkaHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.nio.charset.StandardCharsets;

/** Dựng {@link ConsumerRecord} có header FINORA cho test consumer (consumer nhận record từ đợt REFACTOR-001). */
final class KafkaTestRecords {

    private KafkaTestRecords() {
    }

    static ConsumerRecord<String, String> record(String value, String eventId, String eventType, String version) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test-topic", 0, 0L, null, value);
        add(record, FinoraKafkaHeaders.EVENT_ID, eventId);
        add(record, FinoraKafkaHeaders.EVENT_TYPE, eventType);
        add(record, FinoraKafkaHeaders.EVENT_VERSION, version);
        return record;
    }

    private static void add(ConsumerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
