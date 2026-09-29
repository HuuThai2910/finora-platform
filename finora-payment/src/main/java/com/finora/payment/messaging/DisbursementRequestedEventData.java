package com.finora.payment.messaging;
import java.util.List;
import java.util.UUID;
public record DisbursementRequestedEventData(UUID sagaId,Long loanApplicationId,String applicationNumber,
 String contractNumber,Long listingId,String borrowerId,String amount,String currency,List<Allocation> allocations){
 public record Allocation(Long commitmentId,String investorId,String amount,String paymentHoldReference){}
}
