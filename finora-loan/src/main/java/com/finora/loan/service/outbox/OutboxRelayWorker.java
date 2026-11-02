package com.finora.loan.service.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Relay chỉ khởi động khi operator bật cờ explicit. Không dùng @ConditionalOnBean(OutboxPublisher.class): publisher là
 * @Bean của KafkaOutboxConfiguration, trong fat jar component được quét trước nên điều kiện sai và worker bị bỏ, outbox
 * nằm PENDING mãi (hồ sơ đã duyệt không lên sàn). Thiếu publisher thì OutboxRelayService tự xử lý qua ObjectProvider.
 */
@Component
@ConditionalOnProperty(name = "finora.loan.outbox.publisher-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayWorker {

    private final OutboxRelayStateService stateService;
    private final OutboxRelayService relayService;

    @Scheduled(fixedDelayString = "${finora.loan.outbox.publisher-delay:5000}")
    public void publishDueEvents() {
        for (Long eventId : stateService.dueIds()) {
            try {
                relayService.publish(eventId);
            } catch (RuntimeException failure) {
                log.error("Outbox relay chưa xử lý được event: databaseId={}, exceptionType={}",
                        eventId, failure.getClass().getName());
            }
        }
    }
}
