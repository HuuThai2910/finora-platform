package com.finora.payment.integration.provider;
import java.math.BigDecimal;
import java.util.UUID;
public interface PaymentProvider {
 String name();
 PaymentProviderResult disburse(UUID sagaId,String borrowerId,BigDecimal amount,String currency);
 record PaymentProviderResult(boolean success,String reference,String errorCode,String errorMessage,boolean retryable){
  public static PaymentProviderResult success(String reference){return new PaymentProviderResult(true,reference,null,null,false);}
  public static PaymentProviderResult failure(String code,String message,boolean retryable){return new PaymentProviderResult(false,null,code,message,retryable);}
 }
}

