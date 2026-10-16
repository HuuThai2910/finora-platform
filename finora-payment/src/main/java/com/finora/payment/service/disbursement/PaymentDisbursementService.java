package com.finora.payment.service.disbursement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.payment.config.PaymentDisbursementProperties;
import com.finora.payment.domain.hold.PaymentHold;
import com.finora.payment.domain.hold.PaymentHoldStatus;
import com.finora.payment.domain.ledger.LedgerBalanceBucket;
import com.finora.payment.domain.ledger.LedgerDirection;
import com.finora.payment.domain.ledger.LedgerTransactionType;
import com.finora.payment.domain.disbursement.*;
import com.finora.payment.domain.messaging.PaymentProcessedEvent;
import com.finora.payment.domain.outbox.PaymentOutboxEvent;
import com.finora.payment.domain.wallet.WalletOwnerType;
import com.finora.payment.integration.provider.PaymentProvider;
import com.finora.payment.messaging.*;
import com.finora.payment.repository.*;
import com.finora.payment.repository.hold.PaymentHoldRepository;
import com.finora.payment.service.ledger.LedgerPostingCommand;
import com.finora.payment.service.ledger.LedgerPostingEntryCommand;
import com.finora.payment.service.ledger.LedgerPostingResult;
import com.finora.payment.service.ledger.LedgerPostingService;
import com.finora.payment.service.wallet.WalletAccountService;
import com.finora.payment.service.wallet.WalletView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j @Service @RequiredArgsConstructor
public class PaymentDisbursementService {
 private final PaymentDisbursementRepository repository;
 private final PaymentProcessedEventRepository processedRepository;
 private final PaymentOutboxEventRepository outboxRepository;
 private final PaymentHoldRepository holdRepository;
 private final LedgerPostingService ledgerPostingService;
 private final WalletAccountService walletAccountService;
 private final PaymentProvider provider;
 private final ObjectMapper objectMapper;
 private final Clock clock;
 private final TransactionTemplate transactionTemplate;
 private final PaymentDisbursementProperties properties;

 @Transactional
 public void request(UUID eventId,DisbursementRequestedEventData data){
  if(processedRepository.existsByEventId(eventId))return;
  validate(data);
  BigDecimal amount=new BigDecimal(data.amount()).setScale(2);
  String snapshot=serialize(data.allocations());
  PaymentDisbursement existing=repository.findBySagaId(data.sagaId()).orElse(null);
  if(existing==null){
   repository.save(PaymentDisbursement.requested(data.sagaId(),data.loanApplicationId(),data.contractNumber(),
    data.listingId(),data.borrowerId(),amount,data.currency(),provider.name(),snapshot,clock.instant()));
  }else{
   requireSameRequest(existing,data,amount,snapshot);
  }
  processedRepository.save(PaymentProcessedEvent.of(eventId,"DisbursementRequested",data.sagaId().toString(),clock.instant()));
 }

 /**
  * Lệnh cần xử lý: mới/chờ retry, đang gọi provider nhưng đã hết lease (worker chết), hoặc đến lượt tra cứu đối soát.
  * Truy vấn sắp theo {@code next_attempt_at, id} nên bản ghi lỗi lặp lại không chặn các bản ghi khác.
  */
 @Transactional(readOnly=true)
 public List<Long> dueIds(){return repository.findDueIds(List.of(PaymentDisbursementStatus.REQUESTED,
   PaymentDisbursementStatus.RETRY_PENDING,PaymentDisbursementStatus.PROCESSING,
   PaymentDisbursementStatus.RECONCILIATION_REQUIRED),clock.instant(),PageRequest.of(0,20));}

 @Transactional(readOnly=true)
 public List<Long> completedAwaitingBorrowerCreditIds(){
  return repository.findCompletedAwaitingBorrowerCreditIds(PageRequest.of(0,20));
 }

 /**
  * Khóa lệnh và quyết định bước kế tiếp. Lệnh mới/chờ retry được giữ chỗ capture và nhận lease rồi mới gọi provider
  * ngoài transaction. Lệnh còn {@code PROCESSING} sau lease chuyển sang đối soát: không biết tiền đã chuyển hay chưa
  * nên tuyệt đối không gọi {@code disburse} lại.
  */
 @Transactional
 public Work claim(Long id){
  PaymentDisbursement d=repository.findByIdForUpdate(id).orElseThrow();
  Instant now=clock.instant();
  if(d.isProcessingLeaseExpired(now)){
   log.warn("Lệnh giải ngân hết lease khi đang gọi provider, chuyển đối soát: sagaId={}",d.getSagaId());
   d.requireReconciliation("PAYMENT_PROCESSING_INTERRUPTED",
    "Worker dừng khi đang gọi provider; tra cứu kết quả theo sagaId trước khi xử lý tiếp",now,now);
   return Work.reconcile(d);
  }
  if(d.getStatus()==PaymentDisbursementStatus.RECONCILIATION_REQUIRED){
   return d.getNextAttemptAt()!=null&&!d.getNextAttemptAt().isAfter(now)?Work.reconcile(d):null;
  }
  if(d.getStatus()!=PaymentDisbursementStatus.REQUESTED&&d.getStatus()!=PaymentDisbursementStatus.RETRY_PENDING)return null;
  List<DisbursementRequestedEventData.Allocation> allocations=allocations(d);
  List<PaymentHold> holds=lockedHolds(allocations);
  requireMatchingHolds(allocations,holds,d.getAmount());
  holds.forEach(hold->hold.reserveCapture(d.getSagaId().toString(),now));
  d.start(now,now.plus(properties.processingLease()));
  return new Work(d.getId(),d.getSagaId(),d.getBorrowerId(),d.getAmount(),d.getCurrency(),false);
 }

 public void execute(Long id){
  Work work=transactionTemplate.execute(status->claim(id));if(work==null)return;
  if(work.reconcile()){reconcile(work);return;}
  PaymentProvider.PaymentProviderResult result=invokeProvider(work);
  transactionTemplate.executeWithoutResult(status->finish(work.id(),result));
 }

 /** Tra cứu provider ngoài transaction rồi khóa lại để áp kết quả; chưa có kết quả thì hẹn lần tra cứu sau. */
 private void reconcile(Work work){
  java.util.Optional<PaymentProvider.PaymentProviderResult> found;
  try{found=provider.lookup(work.sagaId());}
  catch(RuntimeException exception){
   log.warn("Tra cứu provider lỗi khi đối soát giải ngân: sagaId={}",work.sagaId(),exception);
   found=java.util.Optional.empty();
  }
  java.util.Optional<PaymentProvider.PaymentProviderResult> result=found;
  transactionTemplate.executeWithoutResult(status->applyReconciliation(work.id(),result));
 }

 @Transactional
 public void applyReconciliation(Long id,java.util.Optional<PaymentProvider.PaymentProviderResult> found){
  PaymentDisbursement d=repository.findByIdForUpdate(id).orElseThrow();
  if(d.getStatus()!=PaymentDisbursementStatus.RECONCILIATION_REQUIRED)return;
  Instant now=clock.instant();
  if(found.isPresent()&&!"PAYMENT_PROVIDER_UNCERTAIN".equals(found.get().errorCode())){
   finish(id,found.get());
   return;
  }
  boolean more=d.getReconciliationAttemptCount()+1<properties.reconciliationMaxAttempts();
  d.recordReconciliationCheck(now,more?now.plus(properties.reconciliationInterval()):null);
  if(!more)log.error("Hết lượt tra cứu provider, cần vận hành đối soát giải ngân: sagaId={}",d.getSagaId());
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
  if(!d.acceptsProviderResult()){
   // Kết quả của lần gọi cũ đến sau khi đối soát đã chốt: bỏ qua để không capture hay phát event lần hai.
   log.warn("Bỏ qua kết quả provider đến muộn: sagaId={}, status={}",d.getSagaId(),d.getStatus());
   return;
  }
  if(result.success()){
   List<PaymentHold> holds=lockedHolds(allocations(d));
   LedgerPostingResult posting=capture(d,holds,now);
   creditBorrowerWallet(d);
   holds.forEach(hold->hold.capture(d.getSagaId().toString(),now));
   d.complete(result.reference(),posting.transactionId(),now);
   record(d,"DisbursementCompleted",new DisbursementCompletedEventData(d.getSagaId(),d.getLoanApplicationId(),
    d.getContractNumber(),d.getListingId(),d.getAmount().toPlainString(),d.getCurrency(),d.getProviderReference(),now),now);
  }else{
   List<PaymentHold> holds=lockedHolds(allocations(d));
   if("PAYMENT_PROVIDER_UNCERTAIN".equals(result.errorCode())){
    // Hold giữ CAPTURE_PENDING; worker tra cứu provider theo sagaId thay vì chuyển tiền lại.
    d.requireReconciliation(result.errorCode(),result.errorMessage(),now,now.plus(properties.reconciliationInterval()));
    return;
   }
   holds.forEach(hold->hold.cancelCaptureReservation(d.getSagaId().toString(),now));
   boolean retry=result.retryable()&&d.getAttemptCount()<properties.maxAttempts();
   d.fail(result.errorCode(),result.errorMessage(),retry,retry?now.plus(properties.retryBackoff()):null,now);
   if(!retry)record(d,"DisbursementFailed",new DisbursementFailedEventData(d.getSagaId(),d.getLoanApplicationId(),
    d.getContractNumber(),result.errorCode(),result.errorMessage(),now),now);
  }
 }

 /**
  * Bổ sung bút toán cho dữ liệu đã giải ngân trước khi FINORA nối ví người vay.
  * Không sửa ledger cũ; lệnh mới dùng khóa theo saga nên gọi lại không cộng tiền lần hai.
  */
 @Transactional
 public void reconcileBorrowerCredit(Long id){
  PaymentDisbursement d=repository.findByIdForUpdate(id).orElseThrow();
  if(d.getStatus()!=PaymentDisbursementStatus.COMPLETED)return;
  creditBorrowerWallet(d);
 }

 private LedgerPostingResult capture(PaymentDisbursement disbursement,List<PaymentHold> holds,Instant now){
  List<LedgerPostingEntryCommand> entries=new java.util.ArrayList<>();
  for(PaymentHold hold:holds){
   entries.add(new LedgerPostingEntryCommand(hold.getWallet().getWalletId(),null,LedgerBalanceBucket.HELD,
    LedgerDirection.DEBIT,hold.getAmount()));
  }
  entries.add(new LedgerPostingEntryCommand(null,"DISBURSEMENT_CLEARING",LedgerBalanceBucket.CLEARING,
   LedgerDirection.CREDIT,disbursement.getAmount()));
 return ledgerPostingService.post(new LedgerPostingCommand("CAPTURE:"+disbursement.getSagaId(),
   LedgerTransactionType.DISBURSEMENT,"DISBURSEMENT_SAGA",disbursement.getSagaId().toString(),
   disbursement.getCurrency(),entries));
 }

 private LedgerPostingResult creditBorrowerWallet(PaymentDisbursement disbursement){
  WalletView borrowerWallet=walletAccountService.open(
   WalletOwnerType.BORROWER,disbursement.getBorrowerId(),disbursement.getCurrency());
  List<LedgerPostingEntryCommand> entries=List.of(
   new LedgerPostingEntryCommand(null,"DISBURSEMENT_CLEARING",LedgerBalanceBucket.CLEARING,
    LedgerDirection.DEBIT,disbursement.getAmount()),
   new LedgerPostingEntryCommand(borrowerWallet.walletId(),null,LedgerBalanceBucket.AVAILABLE,
    LedgerDirection.CREDIT,disbursement.getAmount()));
  return ledgerPostingService.post(new LedgerPostingCommand("BORROWER_CREDIT:"+disbursement.getSagaId(),
   LedgerTransactionType.DISBURSEMENT,"DISBURSEMENT_SAGA",disbursement.getSagaId().toString(),
   disbursement.getCurrency(),entries));
 }

 private List<PaymentHold> lockedHolds(List<DisbursementRequestedEventData.Allocation> allocations){
  List<String> references=allocations.stream().map(DisbursementRequestedEventData.Allocation::paymentHoldReference).toList();
  List<PaymentHold> holds=holdRepository.findAllByHoldReferenceInForUpdate(references);
  if(holds.size()!=references.size())throw new IllegalArgumentException("Không tìm đủ khoản tiền đã giữ để giải ngân");
  return holds;
 }

 private void requireMatchingHolds(List<DisbursementRequestedEventData.Allocation> allocations,List<PaymentHold> holds,BigDecimal total){
  java.util.Map<String,PaymentHold> byReference=holds.stream().collect(java.util.stream.Collectors.toMap(PaymentHold::getHoldReference,h->h));
  BigDecimal sum=BigDecimal.ZERO;
  for(DisbursementRequestedEventData.Allocation allocation:allocations){
   PaymentHold hold=byReference.get(allocation.paymentHoldReference());
   BigDecimal amount=new BigDecimal(allocation.amount()).setScale(2);
   if(hold==null||!hold.getOwnerId().equals(allocation.investorId())||hold.getAmount().compareTo(amount)!=0
     ||(hold.getStatus()!=PaymentHoldStatus.HELD&&hold.getStatus()!=PaymentHoldStatus.CAPTURE_PENDING))
    throw new IllegalArgumentException("Allocation không khớp khoản tiền đã giữ");
   sum=sum.add(amount);
  }
  if(sum.compareTo(total)!=0)throw new IllegalArgumentException("Tổng tiền giữ không khớp số tiền giải ngân");
 }

 private List<DisbursementRequestedEventData.Allocation> allocations(PaymentDisbursement d){
  try{return objectMapper.readValue(d.getAllocationSnapshotJson(),new TypeReference<>(){});}
  catch(JsonProcessingException e){throw new IllegalStateException("Allocation snapshot không đọc được",e);}
 }

 private String serialize(Object value){
  try{return objectMapper.writeValueAsString(value);}
  catch(JsonProcessingException e){throw new IllegalStateException("Không serialize được allocation snapshot",e);}
 }

 private void requireSameRequest(PaymentDisbursement existing,DisbursementRequestedEventData data,
   BigDecimal amount,String snapshot){
  if(!existing.getLoanApplicationId().equals(data.loanApplicationId())
    ||!existing.getContractNumber().equals(data.contractNumber())
    ||!existing.getListingId().equals(data.listingId())
    ||!existing.getBorrowerId().equals(data.borrowerId())
    ||existing.getAmount().compareTo(amount)!=0
    ||!existing.getCurrency().equals(data.currency())
    ||!existing.getAllocationSnapshotJson().equals(snapshot)){
   throw new IllegalArgumentException("sagaId đã tồn tại với yêu cầu giải ngân khác");
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
     ||allocation.investorId().isBlank()||allocation.paymentHoldReference()==null
     ||allocation.paymentHoldReference().isBlank()||!commitmentIds.add(allocation.commitmentId()))
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
 /** {@code reconcile = true}: không gọi chuyển tiền, chỉ tra cứu kết quả lần gọi trước theo sagaId. */
 public record Work(Long id,UUID sagaId,String borrowerId,BigDecimal amount,String currency,boolean reconcile){
  static Work reconcile(PaymentDisbursement d){
   return new Work(d.getId(),d.getSagaId(),d.getBorrowerId(),d.getAmount(),d.getCurrency(),true);
  }
 }
}
