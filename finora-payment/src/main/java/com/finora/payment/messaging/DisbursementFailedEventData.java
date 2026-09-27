package com.finora.payment.messaging;
import java.time.Instant;
import java.util.UUID;
public record DisbursementFailedEventData(UUID sagaId,Long loanApplicationId,String contractNumber,
 String errorCode,String errorMessage,Instant failedAt){}

