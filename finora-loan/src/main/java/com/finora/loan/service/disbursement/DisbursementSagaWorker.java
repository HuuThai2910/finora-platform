package com.finora.loan.service.disbursement;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DisbursementSagaWorker {
    private final DisbursementSagaService service;

    @Scheduled(fixedDelayString = "${finora.loan.disbursement.worker-delay:5000}")
    public void run() {
        service.dueIds().forEach(service::execute);
    }
}

