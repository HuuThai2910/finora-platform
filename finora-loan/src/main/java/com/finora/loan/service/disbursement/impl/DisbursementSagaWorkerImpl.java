package com.finora.loan.service.disbursement.impl;

import com.finora.loan.service.disbursement.DisbursementSagaService;
import com.finora.loan.service.disbursement.DisbursementSagaWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker định kỳ ghi các saga giải ngân đến hạn vào core.
 */
@Component
@RequiredArgsConstructor
public class DisbursementSagaWorkerImpl implements DisbursementSagaWorker {
    private final DisbursementSagaService disbursementSagaService;

    @Override
    @Scheduled(fixedDelayString = "${finora.loan.disbursement.worker-delay:5000}")
    public void run() {
        disbursementSagaService.dueIds().forEach(disbursementSagaService::execute);
    }
}
