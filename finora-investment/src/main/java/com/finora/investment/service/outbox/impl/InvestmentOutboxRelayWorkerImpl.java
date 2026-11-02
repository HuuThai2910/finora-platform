package com.finora.investment.service.outbox.impl;

import com.finora.investment.service.outbox.InvestmentOutboxRelayService;
import com.finora.investment.service.outbox.InvestmentOutboxRelayWorker;
import com.finora.investment.service.outbox.InvestmentOutboxStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Không dùng @ConditionalOnBean(InvestmentOutboxPublisher.class): publisher là @Bean của cấu hình, trong fat jar component
 * được quét trước nên điều kiện sai và worker bị bỏ, outbox nằm PENDING mãi. Thiếu publisher thì relay service tự xử lý
 * qua ObjectProvider.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InvestmentOutboxRelayWorkerImpl implements InvestmentOutboxRelayWorker {

    private final InvestmentOutboxStateService stateService;
    private final InvestmentOutboxRelayService relayService;

    @Override
    @Scheduled(fixedDelayString = "${finora.investment.outbox.publisher-delay:5000}")
    public void publishDueEvents() {
        for (Long eventId : stateService.dueIds()) {
            try {
                relayService.publish(eventId);
            } catch (RuntimeException failure) {
                log.error("Investment outbox chưa xử lý được: databaseId={}, exceptionType={}",
                        eventId, failure.getClass().getName());
            }
        }
    }
}
