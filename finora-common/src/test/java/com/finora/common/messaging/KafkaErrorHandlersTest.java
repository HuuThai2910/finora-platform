package com.finora.common.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.backoff.FixedBackOff;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DLT đặt tên đúng ba kiểu đang dùng; exception không retry đi DLT ngay, lỗi hạ tầng vẫn được thử lại. */
class KafkaErrorHandlersTest {

    private static final String TOPIC = "finora.loan.disbursed";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
    private final ConsumerRecord<String, String> failed = new ConsumerRecord<>(TOPIC, 2, 41L, "LA-12", "{broken");

    @Test
    void suffixKeepPartitionPublishesToTopicDotDltOnTheSamePartition() {
        ProducerRecord<Object, Object> deadLetter = recover(KafkaDeadLetterTopic.suffixKeepPartition(".DLT"), null);

        assertThat(deadLetter.topic()).isEqualTo("finora.loan.disbursed.DLT");
        assertThat(deadLetter.partition()).isEqualTo(2);
        assertThat(deadLetter.key()).isEqualTo("LA-12");
        assertThat(deadLetter.value()).isEqualTo("{broken");
    }

    @Test
    void suffixAnyPartitionLetsTheProducerChooseThePartition() {
        ProducerRecord<Object, Object> deadLetter =
                recover(KafkaDeadLetterTopic.suffixAnyPartition(".payment.DLT"), null);

        assertThat(deadLetter.topic()).isEqualTo("finora.loan.disbursed.payment.DLT");
        assertThat(deadLetter.partition()).isNull();
    }

    @Test
    void fixedTopicKeepsThePartitionLikeTheCicRelay() {
        ProducerRecord<Object, Object> deadLetter =
                recover(KafkaDeadLetterTopic.fixedKeepPartition("finora.loan.delinquency-changed.cic.DLT"), null);

        assertThat(deadLetter.topic()).isEqualTo("finora.loan.delinquency-changed.cic.DLT");
        assertThat(deadLetter.partition()).isEqualTo(2);
    }

    @Test
    void beforeDeadLetterHookSeesTheRecordAndExceptionBeforePublishing() {
        List<String> calls = new ArrayList<>();
        BiConsumer<ConsumerRecord<?, ?>, Exception> hook = (record, exception) -> calls.add(record.topic() + "@"
                + record.offset() + ":" + KafkaErrorHandlers.rootCauseSimpleName(exception));

        recover(KafkaDeadLetterTopic.suffixAnyPartition(".notification.DLT"), hook);

        assertThat(calls).containsExactly(TOPIC + "@41:NumberFormatException");
    }

    @Test
    void notRetryableExceptionsSkipRetryWhileInfrastructureErrorsKeepTheirDefault() {
        DefaultErrorHandler handler = KafkaErrorHandlers.deadLetterErrorHandler(template, new FixedBackOff(1_000L, 2L),
                KafkaDeadLetterTopic.suffixKeepPartition(".DLT"), IllegalArgumentException.class,
                IllegalStateException.class);

        // removeClassification trả classification trước đó: false = không retry, null = theo mặc định (retry).
        assertThat(handler.removeClassification(IllegalArgumentException.class)).isFalse();
        assertThat(handler.removeClassification(IllegalStateException.class)).isFalse();
        assertThat(handler.removeClassification(DataAccessResourceFailureException.class)).isNull();
    }

    @Test
    void handlerWithHookAlsoMarksNotRetryableExceptions() {
        DefaultErrorHandler handler = KafkaErrorHandlers.deadLetterErrorHandler(template, new FixedBackOff(1_000L, 2L),
                KafkaDeadLetterTopic.suffixAnyPartition(".payment.DLT"), (record, exception) -> { },
                IllegalArgumentException.class);

        assertThat(handler.removeClassification(IllegalArgumentException.class)).isFalse();
    }

    @Test
    void rootCauseSimpleNameWalksTheWholeCauseChain() {
        Exception exception = new IllegalStateException("outer", new RuntimeException("middle",
                new NumberFormatException("root")));

        assertThat(KafkaErrorHandlers.rootCauseSimpleName(exception)).isEqualTo("NumberFormatException");
        assertThat(KafkaErrorHandlers.rootCauseSimpleName(new IllegalArgumentException("only")))
                .isEqualTo("IllegalArgumentException");
    }

    private ProducerRecord<Object, Object> recover(KafkaDeadLetterTopic deadLetterTopic,
            BiConsumer<ConsumerRecord<?, ?>, Exception> hook) {
        @SuppressWarnings("unchecked")
        SendResult<Object, Object> sendResult = mock(SendResult.class);
        when(template.send(ArgumentMatchers.<ProducerRecord<Object, Object>>any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));
        DeadLetterPublishingRecoverer recoverer = KafkaErrorHandlers.deadLetterRecoverer(template, deadLetterTopic,
                hook);

        recoverer.accept(failed, null, new IllegalArgumentException("bad", new NumberFormatException("x")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<Object, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        return captor.getValue();
    }
}
