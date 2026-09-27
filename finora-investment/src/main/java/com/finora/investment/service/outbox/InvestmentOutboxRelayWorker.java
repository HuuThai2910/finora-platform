package com.finora.investment.service.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(InvestmentOutboxPublisher.class)
@RequiredArgsConstructor
@Slf4j
public class InvestmentOutboxRelayWorker {

    private final InvestmentOutboxStateService stateService;
    private final InvestmentOutboxRelayService relayService;

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
