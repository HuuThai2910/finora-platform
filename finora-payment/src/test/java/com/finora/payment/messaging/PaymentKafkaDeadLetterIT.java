package com.finora.payment.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.payment.domain.disbursement.PaymentDisbursement;
import com.finora.payment.repository.PaymentDisbursementRepository;
import com.finora.payment.repository.servicing.PaymentLoanAccountRepository;
import com.finora.payment.service.servicing.PaymentServicingProjectionService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * B58 trên broker Kafka trong JVM với listener thật của Payment: contract sai vào {@code <topic>.payment.DLT} ngay (không
 * thử lại), lỗi hạ tầng được thử lại có giới hạn rồi mới vào DLT, partition chạy tiếp sau record lỗi. Record hợp lệ mang
 * thêm field P2/P3 (W3F) mà Payment không khai báo vẫn được nhận (consumer bỏ qua field lạ).
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "finora.payment.scheduling-enabled=false",
        "finora.operations.metrics.initial-delay=1h",
        // Thu ngắn lịch thử lại để test nhanh; mặc định 1 giây → 30 giây, tối đa 3 phút.
        "finora.payment.kafka.retry-initial-interval=100ms",
        "finora.payment.kafka.retry-max-interval=200ms",
        "finora.payment.kafka.retry-max-elapsed=1s"
})
@EmbeddedKafka(partitions = 1, topics = {
        PaymentKafkaDeadLetterIT.TOPIC, PaymentKafkaDeadLetterIT.DLT,
        "finora.loan.disbursement-requested", "finora.investment.note-ownership-changed"
})
@Testcontainers
class PaymentKafkaDeadLetterIT {

    static final String TOPIC = "finora.loan.disbursed";
    static final String DLT = "finora.loan.disbursed.payment.DLT";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_payment_kafka_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private ObjectMapper mapper;
    @Autowired private PaymentDisbursementRepository disbursements;
    @Autowired private PaymentLoanAccountRepository loanAccounts;
    @SpyBean private PaymentServicingProjectionService servicing;

    @Test
    void contractErrorsSkipRetryInfrastructureErrorsRetryBoundedAndThePartitionKeepsWorking() throws Exception {
        UUID knownSaga = completedDisbursement(561L);
        UUID mismatchEvent = UUID.randomUUID();
        UUID unknownSagaEvent = UUID.randomUUID();
        UUID validEvent = UUID.randomUUID();

        // 1. JSON hỏng; 2. sai hợp đồng so với giao dịch giải ngân (từ chối chắc chắn); 3. Payment chưa có kết quả giải
        // ngân của saga (lỗi có thể tự hết, thử lại); 4. event hợp lệ đi sau ba record lỗi trên cùng partition.
        kafka.send(new ProducerRecord<>(TOPIC, "k1-broken", "{broken")).get();
        kafka.send(record("k2-mismatch", mismatchEvent, data(knownSaga, 561L, "LC-OTHER"))).get();
        kafka.send(record("k3-unknown-saga", unknownSagaEvent, data(UUID.randomUUID(), 562L, "LC-562"))).get();
        kafka.send(record("k4-valid", validEvent, data(knownSaga, 561L, "LC-561"))).get();

        await().atMost(Duration.ofSeconds(60)).until(() -> loanAccounts.findByLoanApplicationId(561L).isPresent());

        List<ConsumerRecord<String, String>> deadLetters = readDeadLetters(3);
        assertThat(deadLetters).extracting(ConsumerRecord::key)
                .containsExactly("k1-broken", "k2-mismatch", "k3-unknown-saga");
        assertThat(deadLetters.getFirst().value()).isEqualTo("{broken");
        assertThat(header(deadLetters.get(0), KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(IllegalArgumentException.class.getName());
        assertThat(header(deadLetters.get(1), KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(IllegalArgumentException.class.getName());
        assertThat(header(deadLetters.get(2), KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(header(deadLetters.get(2), KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
        // Contract sai: đúng một lần, không thử lại. Lỗi hạ tầng: thử lại nhiều lần rồi mới vào DLT (có giới hạn).
        verify(servicing, times(1)).activateLoan(eq(mismatchEvent), any());
        verify(servicing, atLeast(2)).activateLoan(eq(unknownSagaEvent), any());
        verify(servicing, times(1)).activateLoan(eq(validEvent), any());
        assertThat(loanAccounts.findByLoanApplicationId(561L).orElseThrow().getFineractLoanId()).isEqualTo(761L);
        assertThat(loanAccounts.findByLoanApplicationId(562L)).isEmpty();
    }

    private UUID completedDisbursement(long applicationId) {
        UUID sagaId = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-01T00:00:00Z");
        PaymentDisbursement disbursement = PaymentDisbursement.requested(sagaId, applicationId, "LC-" + applicationId,
                applicationId + 100, "BORROWER-" + applicationId, new BigDecimal("10000000.00"), "VND", "MOCK", "[]", at);
        disbursement.start(at, at.plusSeconds(120));
        disbursement.complete("MOCK-" + applicationId, UUID.randomUUID(), at);
        disbursements.saveAndFlush(disbursement);
        return sagaId;
    }

    /** {@code LoanDisbursed.v1} như Loan phát sau W3F: thêm borrowerId (P2) và kỳ đầu (P3) mà Payment không khai báo. */
    private Map<String, Object> data(UUID sagaId, long applicationId, String contractNumber) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sagaId", sagaId.toString());
        data.put("loanApplicationId", applicationId);
        data.put("applicationNumber", "LA-" + applicationId);
        data.put("contractNumber", contractNumber);
        data.put("listingId", applicationId + 100);
        data.put("amount", "10000000.00");
        data.put("currency", "VND");
        data.put("paymentReference", "MOCK-" + applicationId);
        data.put("fineractLoanId", applicationId + 200);
        data.put("coreConfigVersion", "FINORA-FINERACT-V4");
        data.put("disbursedAt", "2026-10-01T00:00:00Z");
        data.put("earlySettlementPolicyVersion", "AGRIBANK_REFERENCE_FINORA_2026_V2");
        data.put("loanNumber", "LN-" + applicationId);
        data.put("maturityDate", "2027-04-01");
        data.put("borrowerId", "BORROWER-" + applicationId);
        data.put("nextDueDate", "2026-11-01");
        data.put("nextDueAmount", "1726000.00");
        return data;
    }

    private ProducerRecord<String, String> record(String key, UUID eventId, Map<String, Object> data) throws Exception {
        String envelope = mapper.writeValueAsString(Map.of("eventId", eventId.toString(),
                "occurredAt", "2026-10-01T00:00:01Z", "version", 1, "data", data));
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, envelope);
        record.headers().add("finora-event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("finora-event-type", "LoanDisbursed".getBytes(StandardCharsets.UTF_8));
        record.headers().add("finora-event-version", "1".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private List<ConsumerRecord<String, String>> readDeadLetters(int expected) {
        Map<String, Object> properties = KafkaTestUtils.consumerProps("payment-dlt-reader", "false", broker);
        properties.put("auto.offset.reset", "earliest");
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(properties,
                new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, DLT);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
