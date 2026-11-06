package com.finora.loan.service.contract.impl;

import com.finora.loan.service.contract.LoanContractExpiryWorker;
import com.finora.loan.service.contract.LoanContractStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker định kỳ chuyển các hợp đồng quá hạn ký sang EXPIRED (tắt bằng
 * {@code finora.loan.contract.expiry-worker-enabled=false}).
 *
 * <p>Hợp đồng lỗi chỉ được ghi log, không chặn các hợp đồng sau trong cùng vòng.</p>
 */
@Component
@ConditionalOnProperty(
        name = "finora.loan.contract.expiry-worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequiredArgsConstructor
@Slf4j
public class LoanContractExpiryWorkerImpl implements LoanContractExpiryWorker {

    private final LoanContractStateService loanContractStateService;

    @Override
    @Scheduled(fixedDelayString = "${finora.loan.contract.expiry-worker-delay:60000}")
    public void expireDueContracts() {
        for (Long contractId : loanContractStateService.dueIds()) {
            try {
                if (loanContractStateService.expireOne(contractId)) {
                    log.info("Đã chuyển Contract hết hạn: contractId={}", contractId);
                }
            } catch (RuntimeException failure) {
                log.error("Expiry worker chưa xử lý được Contract: contractId={}, exceptionType={}",
                        contractId, failure.getClass().getName());
            }
        }
    }
}
