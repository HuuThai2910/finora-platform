package com.finora.user.service.impl;

import com.finora.user.config.CicMappingProperties;
import com.finora.user.domain.UserProfile;
import com.finora.user.integration.cic.client.CicMappingClient;
import com.finora.user.integration.cic.contract.RegisterBorrowerMappingRequest;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.CicMappingTaskService;
import com.finora.user.service.CicMappingWorker;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "finora.cic.mapping", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class CicMappingWorkerImpl implements CicMappingWorker {

    private final CicMappingTaskService taskService;
    private final UserProfileRepository userProfileRepository;
    private final CicMappingClient client;
    private final CicMappingProperties properties;

    @Override
    @Scheduled(fixedDelayString = "${finora.cic.mapping.worker-delay:5000}")
    public void run() {
        taskService.dueIds().forEach(this::executeSafely);
    }

    void executeSafely(Long taskId) {
        CicMappingTaskService.ClaimedTask task = taskService.claim(taskId);
        if (task == null) {
            return;
        }
        try {
            UserProfile profile = userProfileRepository.findById(task.userProfileId())
                    .orElseThrow(() -> new PermanentMappingException("PROFILE_NOT_FOUND"));
            requireIdentity(profile);
            requireApiKey();

            // B26: borrowerId trong event Loan/Payment/Investment là claim `user_id` = user_profiles.id, không phải
            // keycloakUserId. Mapping phải dùng cùng định danh để CIC khớp được event nợ quá hạn với CCCD.
            client.register(
                    properties.getInternalApiKey(),
                    new RegisterBorrowerMappingRequest(
                            String.valueOf(profile.getId()),
                            profile.getIdNumberEncrypted()));
            taskService.complete(task.taskId());
            log.info("Đã đồng bộ ánh xạ CIC: taskId={}, userProfileId={}",
                    task.taskId(), task.userProfileId());
        } catch (PermanentMappingException exception) {
            taskService.fail(task.taskId(), exception.errorCode, false);
            log.error("Không thể đồng bộ ánh xạ CIC: taskId={}, userProfileId={}, errorCode={}",
                    task.taskId(), task.userProfileId(), exception.errorCode);
        } catch (FeignException exception) {
            String errorCode = "CIC_HTTP_" + exception.status();
            boolean retryable = exception.status() < 0
                    || exception.status() == 401
                    || exception.status() == 403
                    || exception.status() == 408
                    || exception.status() == 429
                    || exception.status() >= 500;
            taskService.fail(task.taskId(), errorCode, retryable);
            log.warn("CIC từ chối ánh xạ: taskId={}, userProfileId={}, status={}",
                    task.taskId(), task.userProfileId(), exception.status());
        } catch (RuntimeException exception) {
            taskService.fail(task.taskId(), "CIC_UNAVAILABLE", true);
            // Không log request/exception body vì có thể chứa CCCD.
            log.warn("Chưa đồng bộ được ánh xạ CIC: taskId={}, userProfileId={}, cause={}",
                    task.taskId(), task.userProfileId(), exception.getClass().getSimpleName());
        }
    }

    private static void requireIdentity(UserProfile profile) {
        if (profile.getId() == null
                || profile.getIdNumberEncrypted() == null
                || profile.getIdNumberEncrypted().isBlank()) {
            throw new PermanentMappingException("PROFILE_IDENTITY_MISSING");
        }
    }

    private void requireApiKey() {
        if (properties.getInternalApiKey() == null || properties.getInternalApiKey().isBlank()) {
            throw new IllegalStateException("CIC internal API key chưa được cấu hình");
        }
    }

    private static final class PermanentMappingException extends RuntimeException {
        private final String errorCode;

        private PermanentMappingException(String errorCode) {
            super(errorCode);
            this.errorCode = errorCode;
        }
    }
}
