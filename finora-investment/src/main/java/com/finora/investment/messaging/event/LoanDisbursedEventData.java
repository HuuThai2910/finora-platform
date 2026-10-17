package com.finora.investment.messaging.event;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
/**
 * {@code LoanDisbursed.v1}. {@code loanNumber} và {@code maturityDate} là field optional (B34): mã khoản vay servicing và
 * ngày đáo hạn trên core; event của Loan bản cũ không có thì vị thế chưa có ngày đáo hạn như trước.
 */
public record LoanDisbursedEventData(UUID sagaId,Long loanApplicationId,String applicationNumber,
 String contractNumber,Long listingId,String amount,String currency,String paymentReference,
 Long fineractLoanId,String coreConfigVersion,Instant disbursedAt,String loanNumber,LocalDate maturityDate){
 public LoanDisbursedEventData(UUID sagaId,Long loanApplicationId,String applicationNumber,
   String contractNumber,Long listingId,String amount,String currency,String paymentReference,
   Long fineractLoanId,String coreConfigVersion,Instant disbursedAt){
  this(sagaId,loanApplicationId,applicationNumber,contractNumber,listingId,amount,currency,paymentReference,
   fineractLoanId,coreConfigVersion,disbursedAt,null,null);
 }
}

