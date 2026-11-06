package com.finora.notification.service.intake.impl;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.domain.enums.DeferredEventStatus;
import com.finora.notification.repository.DeferredEventRepository;
import com.finora.notification.service.intake.DeferredEventWorker;
import com.finora.notification.service.intake.NotificationIntakeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Thử lại event đang chờ người nhận (D60), mỗi event một transaction riêng để một dòng lỗi không chặn cả lô.
 * Lỗi contract khi xử lý lại thì dừng hẳn dòng đó (EXPIRED) thay vì thử mãi; lỗi hạ tầng để lượt sau.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeferredEventWorkerImpl implements DeferredEventWorker {

    private final DeferredEventRepository deferredEventRepository;
    private final NotificationIntakeService notificationIntakeService;
    private final NotificationProperties notificationProperties;
    private final Clock clock;

    @Override
    @Scheduled(fixedDelayString = "${finora.notification.deferred.worker-delay:30000}",
            initialDelayString = "${finora.notification.deferred.initial-delay:20000}")
    public void scheduled() {
        runOnce();
    }

    @Override
    public int runOnce() {
        List<Long> ids = deferredEventRepository.findDueIds(DeferredEventStatus.WAITING, clock.instant(),
                PageRequest.of(0, notificationProperties.deferred().batchSize()));
        int processed = 0;
        for (Long id : ids) {
            try {
                notificationIntakeService.retryDeferred(id).ifPresent(result ->
                        log.info("Thử lại event chờ người nhận: deferredId={}, result={}", id, result));
                processed++;
            } catch (IllegalArgumentException contractError) {
                notificationIntakeService.abandonDeferred(id, contractError.getMessage());
            } catch (RuntimeException failure) {
                log.warn("Thử lại event chờ lỗi tạm thời, để lượt sau: deferredId={}, exception={}",
                        id, failure.getClass().getSimpleName());
            }
        }
        return processed;
    }
}
