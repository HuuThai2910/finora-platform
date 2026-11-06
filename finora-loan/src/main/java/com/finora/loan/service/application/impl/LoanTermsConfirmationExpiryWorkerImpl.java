package com.finora.loan.service.application.impl;

import com.finora.loan.service.application.LoanTermsConfirmationExpiryStateService;
import com.finora.loan.service.application.LoanTermsConfirmationExpiryWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker định kỳ chuyển các hồ sơ quá hạn xác nhận điều khoản sang hết hạn (tắt bằng
 * {@code finora.loan.contract.expiry-worker-enabled=false}).
 *
 * <p>Method định kỳ không mở transaction; mỗi hồ sơ đi qua state service để commit riêng.</p>
 */
@Component
@ConditionalOnProperty(
        name = "finora.loan.contract.expiry-worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequiredArgsConstructor
@Slf4j
public class LoanTermsConfirmationExpiryWorkerImpl implements LoanTermsConfirmationExpiryWorker {

    private final LoanTermsConfirmationExpiryStateService loanTermsConfirmationExpiryStateService;

    @Override
    @Scheduled(fixedDelayString = "${finora.loan.contract.expiry-worker-delay:60000}")
    public void expireDueTermsConfirmations() {
        for (Long applicationId : loanTermsConfirmationExpiryStateService.dueIds()) {
            try {
                if (loanTermsConfirmationExpiryStateService.expireOne(applicationId)) {
                    log.info("Đã chuyển xác nhận điều khoản hết hạn: applicationId={}", applicationId);
                }
            } catch (RuntimeException failure) {
                log.error("Terms expiry worker chưa xử lý được hồ sơ: applicationId={}, exceptionType={}",
                        applicationId, failure.getClass().getName());
            }
        }
    }
}
