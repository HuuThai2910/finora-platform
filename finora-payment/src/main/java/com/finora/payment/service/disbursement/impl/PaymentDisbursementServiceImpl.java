package com.finora.payment.service.disbursement.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.payment.config.PaymentDisbursementProperties;
import com.finora.payment.config.PaymentE2EPausePoints;
import com.finora.payment.domain.disbursement.entity.*;
import com.finora.payment.domain.disbursement.enums.*;
import com.finora.payment.domain.hold.entity.PaymentHold;
import com.finora.payment.domain.hold.enums.PaymentHoldStatus;
import com.finora.payment.domain.ledger.enums.LedgerBalanceBucket;
import com.finora.payment.domain.ledger.enums.LedgerDirection;
import com.finora.payment.domain.ledger.enums.LedgerTransactionType;
import com.finora.payment.domain.messaging.entity.PaymentProcessedEvent;
import com.finora.payment.domain.outbox.entity.PaymentOutboxEvent;
import com.finora.payment.domain.wallet.enums.WalletOwnerType;
import com.finora.payment.integration.provider.client.PaymentProvider;
import com.finora.payment.messaging.event.DisbursementCompletedEventData;
import com.finora.payment.messaging.event.DisbursementFailedEventData;
import com.finora.payment.messaging.event.DisbursementRequestedEventData;
import com.finora.payment.repository.*;
import com.finora.payment.repository.hold.PaymentHoldRepository;
import com.finora.payment.service.disbursement.PaymentDisbursementService;
import com.finora.payment.service.ledger.LedgerPostingCommand;
import com.finora.payment.service.ledger.LedgerPostingEntryCommand;
import com.finora.payment.service.ledger.LedgerPostingResult;
import com.finora.payment.service.ledger.LedgerPostingService;
import com.finora.payment.service.wallet.WalletAccountService;
import com.finora.payment.service.wallet.WalletView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Saga giải ngân phía Payment: nhận yêu cầu giải ngân từ Loan, giữ chỗ capture các khoản tiền nhà đầu tư đã giữ, gọi
 * provider chuyển tiền ngoài transaction, đối soát theo sagaId khi chưa rõ kết quả và ghi có ví người vay.
 * <p>
 * Mỗi bước khóa dòng lệnh trong transaction ngắn qua {@code TransactionTemplate}; lời gọi provider nằm ngoài
 * transaction. Provider ném lỗi được coi là chưa rõ kết quả ({@code PAYMENT_PROVIDER_UNCERTAIN}): chỉ tra cứu theo
 * sagaId, không chuyển tiền lại. Bút toán capture và ghi có ví dùng khóa idempotency theo sagaId; event kết quả ghi
 * outbox cùng transaction.
 */
@Slf4j @Service @RequiredArgsConstructor
public class PaymentDisbursementServiceImpl implements PaymentDisbursementService {
 private final PaymentDisbursementRepository paymentDisbursementRepository;
 private final PaymentProcessedEventRepository paymentProcessedEventRepository;
 private final PaymentOutboxEventRepository paymentOutboxEventRepository;
 private final PaymentHoldRepository paymentHoldRepository;
 private final LedgerPostingService ledgerPostingService;
 private final WalletAccountService walletAccountService;
 private final PaymentProvider paymentProvider;
 private final ObjectMapper objectMapper;
 private final Clock clock;
 private final TransactionTemplate transactionTemplate;
 private final PaymentDisbursementProperties paymentDisbursementProperties;
 private final PaymentE2EPausePoints paymentE2EPausePoints;

 @Override
 @Transactional
 public void request(UUID eventId,DisbursementRequestedEventData data){
  if(paymentProcessedEventRepository.existsByEventId(eventId))return;
  validate(data);
  BigDecimal amount=new BigDecimal(data.amount()).setScale(2);
  String snapshot=serialize(data.allocations());
  PaymentDisbursement existing=paymentDisbursementRepository.findBySagaId(data.sagaId()).orElse(null);
  if(existing==null){
   paymentDisbursementRepository.save(PaymentDisbursement.requested(data.sagaId(),data.loanApplicationId(),
    data.contractNumber(),data.listingId(),data.borrowerId(),amount,data.currency(),paymentProvider.name(),snapshot,
    clock.instant()));
  }else{
   requireSameRequest(existing,data,amount,snapshot);
  }
  paymentProcessedEventRepository.save(PaymentProcessedEvent.of(eventId,"DisbursementRequested",
   data.sagaId().toString(),clock.instant()));
 }

 @Override
 @Transactional(readOnly=true)
 public List<Long> dueIds(){return paymentDisbursementRepository.findDueIds(List.of(PaymentDisbursementStatus.REQUESTED,
   PaymentDisbursementStatus.RETRY_PENDING,PaymentDisbursementStatus.PROCESSING,
   PaymentDisbursementStatus.RECONCILIATION_REQUIRED),clock.instant(),PageRequest.of(0,20));}

 @Override
 @Transactional(readOnly=true)
 public List<Long> completedAwaitingBorrowerCreditIds(){
  return paymentDisbursementRepository.findCompletedAwaitingBorrowerCreditIds(PageRequest.of(0,20));
 }

 @Override
 @Transactional
 public Work claim(Long id){
  PaymentDisbursement disbursement=paymentDisbursementRepository.findByIdForUpdate(id).orElseThrow();
  Instant now=clock.instant();
  if(disbursement.isProcessingLeaseExpired(now)){
   log.warn("Lệnh giải ngân hết lease khi đang gọi provider, chuyển đối soát: sagaId={}",disbursement.getSagaId());
   disbursement.requireReconciliation("PAYMENT_PROCESSING_INTERRUPTED",
    "Worker dừng khi đang gọi provider; tra cứu kết quả theo sagaId trước khi xử lý tiếp",now,now);
   return Work.reconcile(disbursement);
  }
  if(disbursement.getStatus()==PaymentDisbursementStatus.RECONCILIATION_REQUIRED){
   return disbursement.getNextAttemptAt()!=null&&!disbursement.getNextAttemptAt().isAfter(now)
    ?Work.reconcile(disbursement):null;
  }
  if(disbursement.getStatus()!=PaymentDisbursementStatus.REQUESTED
   &&disbursement.getStatus()!=PaymentDisbursementStatus.RETRY_PENDING)return null;
  List<DisbursementRequestedEventData.Allocation> allocations=allocations(disbursement);
  List<PaymentHold> holds=lockedHolds(allocations);
  requireMatchingHolds(allocations,holds,disbursement.getAmount());
  holds.forEach(hold->hold.reserveCapture(disbursement.getSagaId().toString(),now));
  disbursement.start(now,now.plus(paymentDisbursementProperties.processingLease()));
  return new Work(disbursement.getId(),disbursement.getSagaId(),disbursement.getBorrowerId(),disbursement.getAmount(),
   disbursement.getCurrency(),false);
 }

 @Override
 public void execute(Long id){
  Work work=transactionTemplate.execute(status->claim(id));if(work==null)return;
  if(work.reconcile()){reconcile(work);return;}
  PaymentProvider.PaymentProviderResult result=invokeProvider(work);
  // Q13: drill E2E tắt Payment đúng lúc provider đã chuyển tiền mà Payment chưa lưu kết quả.
  if(result.success())paymentE2EPausePoints.pause(PaymentE2EPausePoints.PROVIDER_DISBURSED_BEFORE_SAVE);
  transactionTemplate.executeWithoutResult(status->finish(work.id(),result));
 }

 /** Tra cứu provider ngoài transaction rồi khóa lại để áp kết quả; chưa có kết quả thì hẹn lần tra cứu sau. */
 private void reconcile(Work work){
  java.util.Optional<PaymentProvider.PaymentProviderResult> found;
  try{found=paymentProvider.lookup(work.sagaId());}
  catch(RuntimeException exception){
   log.warn("Tra cứu provider lỗi khi đối soát giải ngân: sagaId={}",work.sagaId(),exception);
   found=java.util.Optional.empty();
  }
  java.util.Optional<PaymentProvider.PaymentProviderResult> result=found;
  transactionTemplate.executeWithoutResult(status->applyReconciliation(work.id(),result));
 }

 @Override
 @Transactional
 public void applyReconciliation(Long id,java.util.Optional<PaymentProvider.PaymentProviderResult> found){
  PaymentDisbursement disbursement=paymentDisbursementRepository.findByIdForUpdate(id).orElseThrow();
  if(disbursement.getStatus()!=PaymentDisbursementStatus.RECONCILIATION_REQUIRED)return;
  Instant now=clock.instant();
  if(found.isPresent()&&!"PAYMENT_PROVIDER_UNCERTAIN".equals(found.get().errorCode())){
   finish(id,found.get());
   return;
  }
  boolean more=disbursement.getReconciliationAttemptCount()+1<paymentDisbursementProperties.reconciliationMaxAttempts();
  disbursement.recordReconciliationCheck(now,
   more?now.plus(paymentDisbursementProperties.reconciliationInterval()):null);
  if(!more)log.error("Hết lượt tra cứu provider, cần vận hành đối soát giải ngân: sagaId={}",disbursement.getSagaId());
 }

 private PaymentProvider.PaymentProviderResult invokeProvider(Work work){
  try{
   return paymentProvider.disburse(work.sagaId(),work.borrowerId(),work.amount(),work.currency());
  }catch(RuntimeException exception){
   // Không retry mù khi provider ném lỗi: lệnh có thể đã tới provider nhưng response bị mất.
   // Đưa sang xử lý/đối soát để không có nguy cơ chuyển tiền lần hai.
   return PaymentProvider.PaymentProviderResult.failure("PAYMENT_PROVIDER_UNCERTAIN",
    "Không xác định được kết quả từ provider; cần đối soát theo sagaId",false);
  }
 }

 @Override
 @Transactional
 public void finish(Long id,PaymentProvider.PaymentProviderResult result){
  PaymentDisbursement disbursement=paymentDisbursementRepository.findByIdForUpdate(id).orElseThrow();
  Instant now=clock.instant();
  if(!disbursement.acceptsProviderResult()){
   // Kết quả của lần gọi cũ đến sau khi đối soát đã chốt: bỏ qua để không capture hay phát event lần hai.
   log.warn("Bỏ qua kết quả provider đến muộn: sagaId={}, status={}",disbursement.getSagaId(),disbursement.getStatus());
   return;
  }
  if(result.success()){
   List<PaymentHold> holds=lockedHolds(allocations(disbursement));
   LedgerPostingResult posting=capture(disbursement,holds,now);
   creditBorrowerWallet(disbursement);
   holds.forEach(hold->hold.capture(disbursement.getSagaId().toString(),now));
   disbursement.complete(result.reference(),posting.transactionId(),now);
   record(disbursement,"DisbursementCompleted",new DisbursementCompletedEventData(disbursement.getSagaId(),
    disbursement.getLoanApplicationId(),disbursement.getContractNumber(),disbursement.getListingId(),
    disbursement.getAmount().toPlainString(),disbursement.getCurrency(),disbursement.getProviderReference(),now),now);
  }else{
   List<PaymentHold> holds=lockedHolds(allocations(disbursement));
   if("PAYMENT_PROVIDER_UNCERTAIN".equals(result.errorCode())){
    // Hold giữ CAPTURE_PENDING; worker tra cứu provider theo sagaId thay vì chuyển tiền lại.
    disbursement.requireReconciliation(result.errorCode(),result.errorMessage(),now,
     now.plus(paymentDisbursementProperties.reconciliationInterval()));
    return;
   }
   holds.forEach(hold->hold.cancelCaptureReservation(disbursement.getSagaId().toString(),now));
   boolean retry=result.retryable()&&disbursement.getAttemptCount()<paymentDisbursementProperties.maxAttempts();
   disbursement.fail(result.errorCode(),result.errorMessage(),retry,
    retry?now.plus(paymentDisbursementProperties.retryBackoff()):null,now);
   if(!retry)record(disbursement,"DisbursementFailed",new DisbursementFailedEventData(disbursement.getSagaId(),
    disbursement.getLoanApplicationId(),disbursement.getContractNumber(),result.errorCode(),result.errorMessage(),
    now),now);
  }
 }

 @Override
 @Transactional
 public void reconcileBorrowerCredit(Long id){
  PaymentDisbursement disbursement=paymentDisbursementRepository.findByIdForUpdate(id).orElseThrow();
  if(disbursement.getStatus()!=PaymentDisbursementStatus.COMPLETED)return;
  creditBorrowerWallet(disbursement);
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
  List<PaymentHold> holds=paymentHoldRepository.findAllByHoldReferenceInForUpdate(references);
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

 private List<DisbursementRequestedEventData.Allocation> allocations(PaymentDisbursement disbursement){
  try{return objectMapper.readValue(disbursement.getAllocationSnapshotJson(),new TypeReference<>(){});}
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

 private void record(PaymentDisbursement disbursement,String type,Object payload,Instant now){
  try{paymentOutboxEventRepository.save(PaymentOutboxEvent.pending(disbursement.getSagaId().toString(),type,
   objectMapper.writeValueAsString(payload),now));}
  catch(JsonProcessingException e){throw new IllegalStateException("Không serialize được payment event",e);}
 }
 private void validate(DisbursementRequestedEventData data){
  if(data==null||data.sagaId()==null||data.loanApplicationId()==null||data.listingId()==null
    ||data.contractNumber()==null
    ||data.borrowerId()==null||!"VND".equals(data.currency())||data.allocations()==null||data.allocations().isEmpty()
    )throw new IllegalArgumentException("DisbursementRequested không hợp lệ");
  BigDecimal requested;
  try{requested=new BigDecimal(data.amount());}
  catch(RuntimeException exception){throw new IllegalArgumentException("DisbursementRequested amount không hợp lệ",exception);}
  if(requested.signum()<=0)throw new IllegalArgumentException("DisbursementRequested amount phải dương");
  java.util.Set<Long> commitmentIds=new java.util.HashSet<>();
  BigDecimal allocated=BigDecimal.ZERO;
  for(DisbursementRequestedEventData.Allocation allocation:data.allocations()){
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
}
