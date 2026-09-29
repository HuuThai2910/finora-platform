package com.finora.payment.service.disbursement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
@ConditionalOnProperty(prefix="finora.payment",name="scheduling-enabled",havingValue="true",matchIfMissing=true)
@RequiredArgsConstructor
@Slf4j
public class PaymentDisbursementWorker{
 private final PaymentDisbursementService service;
 @Scheduled(fixedDelayString="${finora.payment.worker-delay:3000}")
 public void run(){
  service.dueIds().forEach(id->runSafely(id,()->service.execute(id),"execute"));
  service.completedAwaitingBorrowerCreditIds()
   .forEach(id->runSafely(id,()->service.reconcileBorrowerCredit(id),"borrower-credit"));
 }

 private void runSafely(Long id,Runnable action,String step){
  try{action.run();}
  catch(RuntimeException exception){
   // Một bản ghi lỗi không được chặn các saga còn lại; worker sẽ thử lại ở vòng sau.
   log.error("Payment worker thất bại: disbursementId={}, step={}",id,step,exception);
  }
 }
}
