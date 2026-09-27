package com.finora.payment.service.disbursement;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class PaymentDisbursementWorker{
 private final PaymentDisbursementService service;
 @Scheduled(fixedDelayString="${finora.payment.worker-delay:3000}") public void run(){service.dueIds().forEach(service::execute);}
}

