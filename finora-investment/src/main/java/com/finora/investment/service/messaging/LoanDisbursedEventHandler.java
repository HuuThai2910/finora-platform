package com.finora.investment.service.messaging;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.messaging.ProcessedEvent;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.messaging.event.LoanDisbursedEventData;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import com.finora.investment.service.NoteIssuanceService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor
public class LoanDisbursedEventHandler{
 private final ProcessedEventRepository processed;private final MarketListingRepository listings;
 private final NoteIssuanceService notes;private final Clock clock;
 @Transactional public void handle(UUID eventId,Instant occurredAt,LoanDisbursedEventData data){
  if(processed.existsByEventId(eventId))return;
  MarketListing listing=listings.findByIdForUpdate(data.listingId()).orElseThrow(()->
   InvestmentDomainException.notFound("LISTING_NOT_FOUND","Không tìm thấy listing cần hoàn tất giải ngân"));
  if(!listing.getLoanId().equals(data.loanApplicationId())||!listing.getApplicationNumber().equals(data.applicationNumber())
    ||!data.contractNumber().equals(listing.getContractNumber())||!"EFFECTIVE".equals(listing.getContractStatus()))
   throw InvestmentDomainException.conflict("LOAN_DISBURSED_MISMATCH","LoanDisbursed không khớp listing/hợp đồng");
  requireFinancialResult(listing,data);
  notes.finalizeCommitments(listing.getId());
  notes.activateNotes(listing.getId());
  listing.setDisbursementSagaId(data.sagaId());listing.setPaymentReference(data.paymentReference());
  listing.setFineractLoanId(data.fineractLoanId());listing.setDisbursedAt(data.disbursedAt());
  listing.setUpdatedBy("SYSTEM-LOAN-EVENT");listing.setUpdatedAt(clock.instant());
  processed.save(ProcessedEvent.create(eventId,"LoanDisbursed",1,"finora-loan",data.contractNumber(),occurredAt,clock.instant()));
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
