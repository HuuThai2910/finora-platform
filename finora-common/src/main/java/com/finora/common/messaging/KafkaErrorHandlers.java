package com.finora.common.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.lang.Nullable;
import org.springframework.util.backoff.BackOff;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * Dựng error handler cho listener Kafka của FINORA: lỗi thử lại theo {@link BackOff} của service, exception không thử
 * lại (contract sai, từ chối nghiệp vụ) vào DLT ngay, hết lượt thử cũng vào DLT; record lỗi không bao giờ bị bỏ qua lặng
 * lẽ. Backoff, danh sách exception không thử lại, tên DLT và log khi vào DLT vẫn do từng service chọn như trước.
 * <p>
 * Ngoài các exception truyền vào, {@link DefaultErrorHandler} vẫn giữ danh sách mặc định không thử lại của Spring Kafka
 * (lỗi deserialize, chuyển kiểu message, {@code ClassCastException}...).
 */
public final class KafkaErrorHandlers {

    private KafkaErrorHandlers() {
    }

    /** Error handler có DLT, không log riêng khi vào DLT (Loan, Investment, Blockchain, relay CIC). */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static DefaultErrorHandler deadLetterErrorHandler(
            KafkaOperations<?, ?> kafkaOperations,
            BackOff backOff,
            KafkaDeadLetterTopic deadLetterTopic,
            Class<? extends Exception>... notRetryableExceptions
    ) {
        return errorHandler(deadLetterRecoverer(kafkaOperations, deadLetterTopic, null), backOff,
                notRetryableExceptions);
    }

    /**
     * Error handler có DLT và gọi {@code beforeDeadLetter} ngay trước khi chọn topic DLT, như Payment và Notification
     * log vị trí record cùng loại lỗi gốc (không log payload) bằng logger của chính service.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static DefaultErrorHandler deadLetterErrorHandler(
            KafkaOperations<?, ?> kafkaOperations,
            BackOff backOff,
            KafkaDeadLetterTopic deadLetterTopic,
            BiConsumer<ConsumerRecord<?, ?>, Exception> beforeDeadLetter,
            Class<? extends Exception>... notRetryableExceptions
    ) {
        Objects.requireNonNull(beforeDeadLetter, "beforeDeadLetter");
        return errorHandler(deadLetterRecoverer(kafkaOperations, deadLetterTopic, beforeDeadLetter), backOff,
                notRetryableExceptions);
    }

    /**
     * Recoverer đẩy record lỗi sang topic DLT bằng {@code kafkaOperations}, giữ nguyên key, value và header gốc (Spring
     * Kafka thêm header {@code kafka_dlt-*}).
     *
     * @param beforeDeadLetter gọi trước khi chọn topic DLT; {@code null} là không gọi gì
     */
    public static DeadLetterPublishingRecoverer deadLetterRecoverer(
            KafkaOperations<?, ?> kafkaOperations,
            KafkaDeadLetterTopic deadLetterTopic,
            @Nullable BiConsumer<ConsumerRecord<?, ?>, Exception> beforeDeadLetter
    ) {
        Objects.requireNonNull(kafkaOperations, "kafkaOperations");
        Objects.requireNonNull(deadLetterTopic, "deadLetterTopic");
        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destination = beforeDeadLetter == null
                ? deadLetterTopic
                : (record, exception) -> {
                    beforeDeadLetter.accept(record, exception);
                    return deadLetterTopic.apply(record, exception);
                };
        return new DeadLetterPublishingRecoverer(kafkaOperations, destination);
    }

    /**
     * Tên class của nguyên nhân sâu nhất trong chuỗi cause, để log loại lỗi gốc mà không log message (message có thể
     * chứa payload).
     */
    public static String rootCauseSimpleName(Throwable exception) {
        Throwable current = Objects.requireNonNull(exception, "exception");
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    private static DefaultErrorHandler errorHandler(
            DeadLetterPublishingRecoverer recoverer,
            BackOff backOff,
            Class<? extends Exception>... notRetryableExceptions
    ) {
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, Objects.requireNonNull(backOff, "backOff"));
        handler.addNotRetryableExceptions(notRetryableExceptions);
        return handler;
    }
}
