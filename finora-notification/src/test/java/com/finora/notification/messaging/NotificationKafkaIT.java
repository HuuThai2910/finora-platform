package com.finora.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.notification.domain.InAppNotification;
import com.finora.notification.repository.InAppNotificationRepository;
import com.finora.notification.repository.ProcessedEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Listener thật trên broker Kafka trong JVM (B62): event hợp lệ được nhận đúng một lần dù Kafka giao trùng; payload hỏng
 * không thử lại mà vào DLT riêng {@code <topic>.notification.DLT}, partition sau đó vẫn chạy tiếp.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "finora.notification.workers-enabled=false"
})
@EmbeddedKafka(partitions = 1, topics = {
        "finora.loan.funding-requested", "finora.investment.loan-fully-funded",
        "finora.investment.loan-funding-expired", "finora.loan.funding-cancelled",
        "finora.loan.investor-signature-requested", "finora.loan.borrower-signature-requested",
        "finora.loan.contract-activated", "finora.loan.disbursed", "finora.payment.repayment-distributed",
        "finora.loan.delinquency-changed", "finora.loan.settled", "finora.loan.reschedule-consent-requested",
        "finora.investment.reschedule-consent-completed", "finora.loan.rescheduled",
        "finora.payment.withdrawal-completed", "finora.payment.withdrawal-failed",
        "finora.investment.note-servicing-changed", "finora.loan.funding-requested.notification.DLT"
})
@Testcontainers
class NotificationKafkaIT {

    private static final String TOPIC = "finora.loan.funding-requested";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_notification_kafka_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private InAppNotificationRepository notifications;
    @Autowired private ProcessedEventRepository processed;
    @Autowired private ObjectMapper mapper;

    @Test
    void duplicateDeliveryIsIgnoredAndBrokenPayloadGoesToTheNotificationDlt() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID afterPoison = UUID.randomUUID();

        kafka.send(record(eventId, 7001L, "kafka-borrower-1")).get();
        kafka.send(record(eventId, 7001L, "kafka-borrower-1")).get();
        kafka.send(new ProducerRecord<>(TOPIC, "7002", "{broken")).get();
        kafka.send(record(afterPoison, 7003L, "kafka-borrower-3")).get();

        // Record sau payload hỏng vẫn được xử lý: lỗi contract không chặn partition.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(notifications.findAll().stream().map(InAppNotification::getRecipientId))
                        .contains("kafka-borrower-1", "kafka-borrower-3"));
        assertThat(notifications.findAll().stream()
                .filter(value -> value.getRecipientId().equals("kafka-borrower-1"))).hasSize(1);
        assertThat(processed.existsByEventId(eventId)).isTrue();

        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                KafkaTestUtils.consumerProps("dlt-reader", "false", broker), new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, TOPIC + ".notification.DLT");
            ConsumerRecord<String, String> dead = KafkaTestUtils.getSingleRecord(consumer,
                    TOPIC + ".notification.DLT", Duration.ofSeconds(30));
            assertThat(dead.value()).isEqualTo("{broken");
            assertThat(new String(dead.headers().lastHeader("kafka_dlt-original-topic").value(),
                    StandardCharsets.UTF_8)).isEqualTo(TOPIC);
        }
    }

    private ProducerRecord<String, String> record(UUID eventId, Long loanId, String borrower)
            throws JsonProcessingException {
        String payload = mapper.writeValueAsString(Map.of("eventId", eventId.toString(),
                "occurredAt", Instant.now().toString(), "version", 1,
                "data", Map.of("loanApplicationId", loanId, "applicationNumber", "LA-2026-00" + loanId,
                        "fundingRound", 1, "targetAmount", "10000000.00", "borrowerId", borrower)));
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, loanId.toString(), payload);
        record.headers().add("finora-event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("finora-event-type", "LoanFundingRequested".getBytes(StandardCharsets.UTF_8));
        record.headers().add("finora-event-version", "1".getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
