package com.finora.loan.service.contract;

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
public class LoanContractExpiryWorker {

    private final LoanContractStateService stateService;

    /** Scheduled method không có transaction; mỗi ID đi qua proxy state service để commit riêng. */
    @Scheduled(fixedDelayString = "${finora.loan.contract.expiry-worker-delay:60000}")
    public void expireDueContracts() {
        for (Long contractId : stateService.dueIds()) {
            try {
                if (stateService.expireOne(contractId)) {
                    log.info("Đã chuyển Contract hết hạn: contractId={}", contractId);
                }
            } catch (RuntimeException failure) {
                log.error("Expiry worker chưa xử lý được Contract: contractId={}, exceptionType={}",
                        contractId, failure.getClass().getName());
            }
        }
    }
}
