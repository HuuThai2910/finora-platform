package com.finora.payment.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.payment.domain.outbox.PaymentOutboxEvent;
import com.finora.payment.repository.PaymentOutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * B11: broker không xác nhận thì worker outbox không được treo; event ở lại {@code PENDING} để gửi lại sau, vì consumer
 * đã chống trùng theo {@code eventId} nên gửi lại là an toàn.
 */
class PaymentOutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-16T21:45:00Z");

    private final PaymentOutboxEventRepository repository = mock(PaymentOutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private PaymentOutboxPublisher publisher;
    private PaymentOutboxEvent event;

    @BeforeEach
    void setUp() {
        publisher = new PaymentOutboxPublisher(repository, kafka, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), new MockEnvironment());
        ReflectionTestUtils.setField(publisher, "publishTimeout", Duration.ofMillis(100));
        event = PaymentOutboxEvent.pending("saga-1", "DisbursementCompleted", "{\"amount\":\"10000000\"}", NOW);
        when(repository.findById(1L)).thenReturn(Optional.of(event));
    }

    @Test
    void brokerThatNeverAcknowledgesTimesOutAndSchedulesRetry() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        long started = System.nanoTime();
        publisher.publishOne(1L);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getAvailableAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(event.getPublishedAt()).isNull();
        verify(repository).save(event);
    }

    @Test
    void acknowledgedSendMarksEventPublished() {
        CompletableFuture<SendResult<String, String>> acknowledged =
                CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(acknowledged);

        publisher.publishOne(1L);

        assertThat(event.getStatus()).isEqualTo("PUBLISHED");
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
        verify(repository).save(event);
    }

    @Test
    void heldInstallmentEventsHaveTheirOwnTopicsKeyedByLoan() {
        // P5 (W3F, D77): key là loanApplicationId như RepaymentDistributed để giữ thứ tự theo khoản vay.
        PaymentOutboxEvent collected = PaymentOutboxEvent.pending("531", "RepaymentCollected",
                "{\"repaymentId\":\"8cbcb833-a674-4ed4-94ae-71619d65e0d8\",\"loanApplicationId\":531}", NOW);
        PaymentOutboxEvent cancelled = PaymentOutboxEvent.pending("531", "RepaymentCancelled",
                "{\"repaymentId\":\"8cbcb833-a674-4ed4-94ae-71619d65e0d8\",\"loanApplicationId\":531}", NOW);
        when(repository.findById(2L)).thenReturn(Optional.of(collected));
        when(repository.findById(3L)).thenReturn(Optional.of(cancelled));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publishOne(2L);
        publisher.publishOne(3L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> records = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(records.capture());
        assertThat(records.getAllValues()).extracting(ProducerRecord::topic)
                .containsExactly("finora.payment.repayment-collected", "finora.payment.repayment-cancelled");
        assertThat(records.getAllValues()).extracting(ProducerRecord::key).containsOnly("531");
        ProducerRecord<String, String> first = records.getAllValues().getFirst();
        assertThat(header(first, "finora-event-type")).isEqualTo("RepaymentCollected");
        assertThat(header(first, "finora-event-version")).isEqualTo("1");
        assertThat(header(first, "finora-event-id")).isEqualTo(collected.getEventId().toString());
        assertThat(collected.getStatus()).isEqualTo("PUBLISHED");
        assertThat(cancelled.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void routeAllowlistIsExactTypeAndVersionAndTopicsAreConfigurable() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("finora.payment.repayment-cancelled-topic", "custom.repayment-cancelled");
        PaymentOutboxPublisher configured = new PaymentOutboxPublisher(repository, kafka, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), environment);

        assertThat(configured.topic("RepaymentCollected", 1)).isEqualTo("finora.payment.repayment-collected");
        assertThat(configured.topic("RepaymentCancelled", 1)).isEqualTo("custom.repayment-cancelled");
        assertThat(configured.topic("RepaymentDistributed", 1)).isEqualTo("finora.payment.repayment-distributed");
        assertThatThrownBy(() -> configured.topic("RepaymentCollected", 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RepaymentCollected v2");
        assertThatThrownBy(() -> configured.topic("RepaymentRefunded", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventVersionWithoutRouteIsNeverSentAndStaysForRetry() {
        PaymentOutboxEvent future = PaymentOutboxEvent.pending("531", "RepaymentCollected", "{}", NOW);
        ReflectionTestUtils.setField(future, "eventVersion", 2);
        when(repository.findById(4L)).thenReturn(Optional.of(future));

        publisher.publishOne(4L);

        verify(kafka, never()).send(any(ProducerRecord.class));
        assertThat(future.getStatus()).isEqualTo("PENDING");
        assertThat(future.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void interruptedWorkerKeepsInterruptFlagAndRetriesLater() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        publisher.publishOne(1L);

        // Thread.interrupted() đọc và xóa cờ, để cờ không rò sang test khác.
        assertThat(Thread.interrupted()).isTrue();
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getAttemptCount()).isEqualTo(1);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
