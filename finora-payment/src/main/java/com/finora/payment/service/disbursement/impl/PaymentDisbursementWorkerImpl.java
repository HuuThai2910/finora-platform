package com.finora.payment.service.disbursement.impl;
import com.finora.payment.service.disbursement.PaymentDisbursementService;
import com.finora.payment.service.disbursement.PaymentDisbursementWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/**
 * Worker định kỳ của saga giải ngân (bật bằng {@code finora.payment.scheduling-enabled}).
 * <p>
 * Mỗi vòng chạy theo {@code finora.payment.worker-delay}: {@code execute} từng lệnh đến hạn rồi
 * {@code reconcileBorrowerCredit} cho lệnh đã xong còn thiếu ghi có ví; lỗi một lệnh chỉ ghi log, vòng sau thử lại.
 */
@Component
@ConditionalOnProperty(prefix="finora.payment",name="scheduling-enabled",havingValue="true",matchIfMissing=true)
@RequiredArgsConstructor
@Slf4j
public class PaymentDisbursementWorkerImpl implements PaymentDisbursementWorker {
 private final PaymentDisbursementService paymentDisbursementService;
 @Override
 @Scheduled(fixedDelayString="${finora.payment.worker-delay:3000}")
 public void run(){
  paymentDisbursementService.dueIds().forEach(id->runSafely(id,()->paymentDisbursementService.execute(id),"execute"));
  paymentDisbursementService.completedAwaitingBorrowerCreditIds()
   .forEach(id->runSafely(id,()->paymentDisbursementService.reconcileBorrowerCredit(id),"borrower-credit"));
 }

 private void runSafely(Long id,Runnable action,String step){
  try{action.run();}
  catch(RuntimeException exception){
   // Một bản ghi lỗi không được chặn các saga còn lại; worker sẽ thử lại ở vòng sau.
   log.error("Payment worker thất bại: disbursementId={}, step={}",id,step,exception);
  }
 }
}
