package com.finora.user.service.impl;

import com.finora.user.config.CicMappingProperties;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.integration.cic.client.CicMappingClient.CicMappingException;
import com.finora.user.integration.cic.client.CicMappingClient;
import com.finora.user.integration.cic.contract.RegisterBorrowerMappingRequest;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.CicMappingTaskService;
import com.finora.user.service.CicMappingWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker định kỳ đăng ký ánh xạ người dùng đã xác minh sang CIC (bật bằng {@code finora.cic.mapping.enabled}).
 * <p>
 * Mỗi task được nhận riêng rồi gửi {@code user_profiles.id} kèm số CCCD sang CIC. Hồ sơ không còn hoặc chưa có
 * CCCD là lỗi vĩnh viễn; lỗi mạng, 401/403/408/429, 5xx hay thiếu khoá API nội bộ thì hẹn chạy lại; task lỗi
 * không chặn task sau.
 */
@Component
@ConditionalOnProperty(prefix = "finora.cic.mapping", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class CicMappingWorkerImpl implements CicMappingWorker {

    private final CicMappingTaskService cicMappingTaskService;
    private final UserProfileRepository userProfileRepository;
    private final CicMappingClient cicMappingClient;
    private final CicMappingProperties cicMappingProperties;

    @Override
    @Scheduled(fixedDelayString = "${finora.cic.mapping.worker-delay:5000}")
    public void run() {
        cicMappingTaskService.dueIds().forEach(this::executeSafely);
    }

    void executeSafely(Long taskId) {
        CicMappingTaskService.ClaimedTask task = cicMappingTaskService.claim(taskId);
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
            cicMappingClient.register(
                    cicMappingProperties.getInternalApiKey(),
                    new RegisterBorrowerMappingRequest(
                            String.valueOf(profile.getId()),
                            profile.getIdNumberEncrypted()));
            cicMappingTaskService.complete(task.taskId());
            log.info("Đã đồng bộ ánh xạ CIC: taskId={}, userProfileId={}",
                    task.taskId(), task.userProfileId());
        } catch (PermanentMappingException exception) {
            cicMappingTaskService.fail(task.taskId(), exception.errorCode, false);
            log.error("Không thể đồng bộ ánh xạ CIC: taskId={}, userProfileId={}, errorCode={}",
                    task.taskId(), task.userProfileId(), exception.errorCode);
        } catch (CicMappingException exception) {
            String errorCode = "CIC_HTTP_" + exception.getStatus();
            boolean retryable = exception.getStatus() < 0
                    || exception.getStatus() == 401
                    || exception.getStatus() == 403
                    || exception.getStatus() == 408
                    || exception.getStatus() == 429
                    || exception.getStatus() >= 500;
            cicMappingTaskService.fail(task.taskId(), errorCode, retryable);
            log.warn("CIC từ chối ánh xạ: taskId={}, userProfileId={}, status={}",
                    task.taskId(), task.userProfileId(), exception.getStatus());
        } catch (RuntimeException exception) {
            cicMappingTaskService.fail(task.taskId(), "CIC_UNAVAILABLE", true);
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
        if (cicMappingProperties.getInternalApiKey() == null || cicMappingProperties.getInternalApiKey().isBlank()) {
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
