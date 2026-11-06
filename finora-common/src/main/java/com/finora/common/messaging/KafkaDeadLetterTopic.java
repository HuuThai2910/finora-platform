package com.finora.common.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Nơi đẩy record lỗi: hàm {@code (record, exception) -> TopicPartition} cho
 * {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}. Partition âm nghĩa là để producer tự chọn
 * partition của topic DLT.
 */
@FunctionalInterface
public interface KafkaDeadLetterTopic extends BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> {

    /**
     * {@code <topic gốc><suffix>}, giữ partition của record gốc: Loan, Investment, Blockchain dùng {@code ".DLT"} (DLT
     * phải có ít nhất bằng số partition của topic gốc).
     */
    static KafkaDeadLetterTopic suffixKeepPartition(String suffix) {
        Objects.requireNonNull(suffix, "suffix");
        return (record, exception) -> new TopicPartition(record.topic() + suffix, record.partition());
    }

    /**
     * {@code <topic gốc><suffix>}, partition để producer tự chọn: Payment ({@code ".payment.DLT"}) và Notification
     * ({@code ".notification.DLT"}) đọc topic dùng chung với service khác nên có DLT riêng, và DLT tự tạo chỉ có một
     * partition.
     */
    static KafkaDeadLetterTopic suffixAnyPartition(String suffix) {
        Objects.requireNonNull(suffix, "suffix");
        return (record, exception) -> new TopicPartition(record.topic() + suffix, -1);
    }

    /** Một topic cố định, giữ partition của record gốc: relay CIC của Loan ({@code dlt-topic} trong cấu hình relay). */
    static KafkaDeadLetterTopic fixedKeepPartition(String topic) {
        Objects.requireNonNull(topic, "topic");
        return (record, exception) -> new TopicPartition(topic, record.partition());
    }
}
