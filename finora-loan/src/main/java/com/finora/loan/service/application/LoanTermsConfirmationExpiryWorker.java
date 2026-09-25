package com.finora.loan.service.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "finora.loan.contract.expiry-worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequiredArgsConstructor
@Slf4j
public class LoanTermsConfirmationExpiryWorker {

    private final LoanTermsConfirmationExpiryStateService stateService;

    @Scheduled(fixedDelayString = "${finora.loan.contract.expiry-worker-delay:60000}")
    public void expireDueTermsConfirmations() {
        for (Long applicationId : stateService.dueIds()) {
            try {
                if (stateService.expireOne(applicationId)) {
                    log.info("Đã chuyển xác nhận điều khoản hết hạn: applicationId={}", applicationId);
                }
            } catch (RuntimeException failure) {
                log.error("Terms expiry worker chưa xử lý được hồ sơ: applicationId={}, exceptionType={}",
                        applicationId, failure.getClass().getName());
            }
        }
    }
}
