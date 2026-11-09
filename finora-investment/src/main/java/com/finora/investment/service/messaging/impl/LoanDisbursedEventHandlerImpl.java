package com.finora.investment.service.messaging.impl;
import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.messaging.entity.ProcessedEvent;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.messaging.event.LoanDisbursedEventData;
import com.finora.investment.messaging.event.NoteOwnershipChangedEventData;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import com.finora.investment.service.NoteIssuanceService;
import com.finora.investment.service.messaging.LoanDisbursedEventHandler;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
/**
 * Xử lý event LoanDisbursed: hoàn tất cam kết, kích hoạt Note, công bố quyền sở hữu và ghi ngày đáo hạn.
 * <p>
 * Chính event này chứng minh Contract đã hiệu lực (Loan chỉ phát sau khi Contract EFFECTIVE và tiền đã chuyển),
 * nên chỉ từ chối khi tham chiếu listing/hợp đồng mâu thuẫn hoặc kết quả giải ngân lệch số tiền.
 */
@Service @RequiredArgsConstructor
public class LoanDisbursedEventHandlerImpl implements LoanDisbursedEventHandler{
 private final ProcessedEventRepository processedEventRepository;private final MarketListingRepository marketListingRepository;
 private final NoteIssuanceService noteIssuanceService;private final InvestmentNoteRepository investmentNoteRepository;
 private final InvestmentOutboxService investmentOutboxService;private final Clock clock;
 private final com.finora.investment.repository.InvestmentLoanServicingStateRepository investmentLoanServicingStateRepository;
 @Override @Transactional public void handle(UUID eventId,Instant occurredAt,LoanDisbursedEventData data){
  if(processedEventRepository.existsByEventId(eventId))return;
  MarketListing listing=marketListingRepository.findByIdForUpdate(data.listingId()).orElseThrow(()->
   InvestmentDomainException.notFound("LISTING_NOT_FOUND","Không tìm thấy listing cần hoàn tất giải ngân"));
  // LoanDisbursed chỉ được Loan phát sau khi Contract EFFECTIVE và tiền đã chuyển, nên chính nó chứng minh Contract
  // hiệu lực. Event Contract (topic khác) đến muộn không được làm Note kẹt trong DLT; chỉ từ chối khi tham chiếu mâu thuẫn.
  if(!listing.getLoanId().equals(data.loanApplicationId())||!listing.getApplicationNumber().equals(data.applicationNumber())
    ||data.contractNumber()==null
    ||(listing.getContractNumber()!=null&&!data.contractNumber().equals(listing.getContractNumber())))
   throw InvestmentDomainException.conflict("LOAN_DISBURSED_MISMATCH","LoanDisbursed không khớp listing/hợp đồng");
  requireFinancialResult(listing,data);
  listing.setContractNumber(data.contractNumber());
  if(listing.getContractCode()==null&&data.contractCode()!=null&&!data.contractCode().isBlank())
    listing.setContractCode(data.contractCode().trim());
  listing.setContractStatus("EFFECTIVE");
  noteIssuanceService.finalizeCommitments(listing.getId());
  noteIssuanceService.activateNotes(listing.getId());
  var ownership = investmentNoteRepository.findByLoanIdAndStatus(listing.getLoanId(), NoteStatus.ACTIVE).stream()
   .map(note -> new NoteOwnershipChangedEventData.NoteOwner(note.getId(), note.getNoteNumber(),
    note.getInvestorId(), note.getOutstandingPrincipal().toPlainString())).toList();
  if(ownership.isEmpty())throw InvestmentDomainException.conflict("NOTE_OWNERSHIP_EMPTY",
   "Không có Note để công bố quyền sở hữu sau giải ngân");
  investmentOutboxService.record("LOAN",listing.getLoanId().toString(),"NoteOwnershipChanged",1,
   new NoteOwnershipChangedEventData(listing.getLoanId(),listing.getId(),"VND","ISSUED",
    data.paymentReference(),data.disbursedAt(),ownership));
  listing.setDisbursementSagaId(data.sagaId());listing.setPaymentReference(data.paymentReference());
  listing.setFineractLoanId(data.fineractLoanId());listing.setDisbursedAt(data.disbursedAt());
  listing.setUpdatedBy("SYSTEM-LOAN-EVENT");listing.setUpdatedAt(clock.instant());
  recordMaturity(data);
  processedEventRepository.save(ProcessedEvent.create(eventId,"LoanDisbursed",1,"finora-loan",data.contractNumber(),occurredAt,clock.instant()));
 }

 /** B34: ngày đáo hạn theo core lúc giải ngân; event Loan bản cũ không có thì giữ như trước (chờ cơ cấu). */
 private void recordMaturity(LoanDisbursedEventData data){
  if(data.maturityDate()==null||data.loanNumber()==null||data.loanNumber().isBlank())return;
  var now=clock.instant();
  var current=investmentLoanServicingStateRepository.findByApplicationIdForUpdate(data.loanApplicationId()).orElse(null);
  if(current==null){
   investmentLoanServicingStateRepository.save(com.finora.investment.domain.note.entity.InvestmentLoanServicingState.disbursed(
    data.loanApplicationId(),data.loanNumber(),data.maturityDate(),now));
  }else{
   current.initializeMaturity(data.maturityDate(),now);
  }
 }

 private void requireFinancialResult(MarketListing listing,LoanDisbursedEventData data){
  BigDecimal amount;
  try{amount=new BigDecimal(data.amount());}
  catch(RuntimeException exception){throw new IllegalArgumentException("LoanDisbursed amount không hợp lệ",exception);}
  if(data.sagaId()==null||data.disbursedAt()==null||data.fineractLoanId()==null||data.fineractLoanId()<=0
    ||data.paymentReference()==null||data.paymentReference().isBlank()||!"VND".equals(data.currency())
    ||amount.compareTo(listing.getTargetAmount())!=0){
   throw InvestmentDomainException.conflict("LOAN_DISBURSED_FINANCIAL_MISMATCH",
    "Kết quả giải ngân không khớp số tiền hoặc thiếu bằng chứng tài chính");
  }
 }
}
