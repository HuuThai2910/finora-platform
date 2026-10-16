package com.finora.payment.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.payment.domain.outbox.PaymentOutboxEvent;
import com.finora.payment.repository.PaymentOutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    void interruptedWorkerKeepsInterruptFlagAndRetriesLater() {
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        publisher.publishOne(1L);

        // Thread.interrupted() đọc và xóa cờ, để cờ không rò sang test khác.
        assertThat(Thread.interrupted()).isTrue();
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getAttemptCount()).isEqualTo(1);
    }
}
