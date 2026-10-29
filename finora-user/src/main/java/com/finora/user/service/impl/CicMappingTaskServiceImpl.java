package com.finora.user.service.impl;

import com.finora.user.config.CicMappingProperties;
import com.finora.user.domain.CicMappingTask;
import com.finora.user.domain.CicMappingTaskStatus;
import com.finora.user.repository.CicMappingTaskRepository;
import com.finora.user.service.CicMappingTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CicMappingTaskServiceImpl implements CicMappingTaskService {

    private static final List<CicMappingTaskStatus> DUE_STATUSES = List.of(
            CicMappingTaskStatus.PENDING,
            CicMappingTaskStatus.PROCESSING,
            CicMappingTaskStatus.RETRY_PENDING);

    private final CicMappingTaskRepository repository;
    private final CicMappingProperties properties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        return repository.findDueIds(
                DUE_STATUSES,
                clock.instant(),
                PageRequest.of(0, Math.max(1, properties.getBatchSize())));
    }

    @Override
    @Transactional
    public ClaimedTask claim(Long taskId) {
        CicMappingTask task = repository.findByIdForUpdate(taskId).orElse(null);
        Instant now = clock.instant();
        if (task == null || !task.isDue(now)) {
            return null;
        }
        task.claim(now, properties.getLeaseDuration());
        return new ClaimedTask(task.getId(), task.getUserProfileId(), task.getAttemptCount());
    }

    @Override
    @Transactional
    public void complete(Long taskId) {
        CicMappingTask task = repository.findByIdForUpdate(taskId).orElseThrow();
        if (task.getStatus() == CicMappingTaskStatus.COMPLETED) {
            return;
        }
        task.complete(clock.instant());
    }

    @Override
    @Transactional
    public void fail(Long taskId, String errorCode, boolean retryable) {
        CicMappingTask task = repository.findByIdForUpdate(taskId).orElseThrow();
        if (task.getStatus() != CicMappingTaskStatus.PROCESSING) {
            return;
        }
        task.fail(
                errorCode,
                retryable,
                Math.max(1, properties.getMaxAttempts()),
                retryDelay(task.getAttemptCount()),
                clock.instant());
    }

    private Duration retryDelay(int attemptCount) {
        int shift = Math.min(Math.max(attemptCount - 1, 0), 10);
        Duration candidate = properties.getInitialRetryDelay().multipliedBy(1L << shift);
        return candidate.compareTo(properties.getMaxRetryDelay()) > 0
                ? properties.getMaxRetryDelay()
                : candidate;
    }
}
