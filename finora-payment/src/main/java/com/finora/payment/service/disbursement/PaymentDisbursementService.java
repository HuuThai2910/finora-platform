package com.finora.payment.service.disbursement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.payment.domain.disbursement.*;
import com.finora.payment.domain.messaging.PaymentProcessedEvent;
import com.finora.payment.domain.outbox.PaymentOutboxEvent;
import com.finora.payment.integration.provider.PaymentProvider;
import com.finora.payment.messaging.*;
import com.finora.payment.repository.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service @RequiredArgsConstructor
public class PaymentDisbursementService {
 private final PaymentDisbursementRepository repository;
 private final PaymentProcessedEventRepository processedRepository;
 private final PaymentOutboxEventRepository outboxRepository;
 private final PaymentProvider provider;
 private final ObjectMapper objectMapper;
 private final Clock clock;
 private final TransactionTemplate transactionTemplate;

 @Transactional
 public void request(UUID eventId,DisbursementRequestedEventData data){
  if(processedRepository.existsByEventId(eventId))return;
  validate(data);
  repository.findBySagaId(data.sagaId()).orElseGet(()->repository.save(PaymentDisbursement.requested(
    data.sagaId(),data.loanApplicationId(),data.contractNumber(),data.listingId(),data.borrowerId(),
    new BigDecimal(data.amount()),data.currency(),provider.name(),clock.instant())));
  processedRepository.save(PaymentProcessedEvent.of(eventId,"DisbursementRequested",data.sagaId().toString(),clock.instant()));
 }

 @Transactional(readOnly=true)
 public List<Long> dueIds(){return repository.findDueIds(List.of(PaymentDisbursementStatus.REQUESTED,
   PaymentDisbursementStatus.RETRY_PENDING),clock.instant(),PageRequest.of(0,20));}

 @Transactional
 public Work claim(Long id){
  PaymentDisbursement d=repository.findByIdForUpdate(id).orElseThrow();
  if(d.getStatus()!=PaymentDisbursementStatus.REQUESTED&&d.getStatus()!=PaymentDisbursementStatus.RETRY_PENDING)return null;
  d.start(clock.instant());return new Work(d.getId(),d.getSagaId(),d.getBorrowerId(),d.getAmount(),d.getCurrency());
 }

 public void execute(Long id){
  Work work=transactionTemplate.execute(status->claim(id));if(work==null)return;
  PaymentProvider.PaymentProviderResult result=invokeProvider(work);
  transactionTemplate.executeWithoutResult(status->finish(work.id(),result));
 }

 private PaymentProvider.PaymentProviderResult invokeProvider(Work work){
  try{
   return provider.disburse(work.sagaId(),work.borrowerId(),work.amount(),work.currency());
  }catch(RuntimeException exception){
   // Không retry mù khi provider ném lỗi: lệnh có thể đã tới provider nhưng response bị mất.
   // Đưa sang xử lý/đối soát để không có nguy cơ chuyển tiền lần hai.
   return PaymentProvider.PaymentProviderResult.failure("PAYMENT_PROVIDER_UNCERTAIN",
    "Không xác định được kết quả từ provider; cần đối soát theo sagaId",false);
  }
 }

 @Transactional
 public void finish(Long id,PaymentProvider.PaymentProviderResult result){
  PaymentDisbursement d=repository.findByIdForUpdate(id).orElseThrow();
  Instant now=clock.instant();
  if(result.success()){
   d.complete(result.reference(),now);
   record(d,"DisbursementCompleted",new DisbursementCompletedEventData(d.getSagaId(),d.getLoanApplicationId(),
    d.getContractNumber(),d.getListingId(),d.getAmount().toPlainString(),d.getCurrency(),d.getProviderReference(),now),now);
  }else{
   boolean retry=result.retryable()&&d.getAttemptCount()<3;
   d.fail(result.errorCode(),result.errorMessage(),retry,retry?now.plus(Duration.ofSeconds(30)):null,now);
   if(!retry)record(d,"DisbursementFailed",new DisbursementFailedEventData(d.getSagaId(),d.getLoanApplicationId(),
    d.getContractNumber(),result.errorCode(),result.errorMessage(),now),now);
  }
 }

 private void record(PaymentDisbursement d,String type,Object payload,Instant now){
  try{outboxRepository.save(PaymentOutboxEvent.pending(d.getSagaId().toString(),type,objectMapper.writeValueAsString(payload),now));}
  catch(JsonProcessingException e){throw new IllegalStateException("Không serialize được payment event",e);}
 }
 private void validate(DisbursementRequestedEventData d){
  if(d==null||d.sagaId()==null||d.loanApplicationId()==null||d.listingId()==null||d.contractNumber()==null
    ||d.borrowerId()==null||!"VND".equals(d.currency())||d.allocations()==null||d.allocations().isEmpty()
    )throw new IllegalArgumentException("DisbursementRequested không hợp lệ");
  BigDecimal requested;
  try{requested=new BigDecimal(d.amount());}
  catch(RuntimeException exception){throw new IllegalArgumentException("DisbursementRequested amount không hợp lệ",exception);}
  if(requested.signum()<=0)throw new IllegalArgumentException("DisbursementRequested amount phải dương");
  java.util.Set<Long> commitmentIds=new java.util.HashSet<>();
  BigDecimal allocated=BigDecimal.ZERO;
  for(DisbursementRequestedEventData.Allocation allocation:d.allocations()){
   if(allocation==null||allocation.commitmentId()==null||allocation.investorId()==null
     ||allocation.investorId().isBlank()||!commitmentIds.add(allocation.commitmentId()))
    throw new IllegalArgumentException("DisbursementRequested allocation không hợp lệ hoặc bị trùng");
   BigDecimal allocationAmount;
   try{allocationAmount=new BigDecimal(allocation.amount());}
   catch(RuntimeException exception){throw new IllegalArgumentException("Allocation amount không hợp lệ",exception);}
   if(allocationAmount.signum()<=0)throw new IllegalArgumentException("Allocation amount phải dương");
   allocated=allocated.add(allocationAmount);
  }
  if(allocated.compareTo(requested)!=0)
   throw new IllegalArgumentException("Tổng allocation không khớp số tiền giải ngân");
 }
 public record Work(Long id,UUID sagaId,String borrowerId,BigDecimal amount,String currency){}
}
