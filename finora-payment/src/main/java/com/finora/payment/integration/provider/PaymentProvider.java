package com.finora.payment.integration.provider;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
public interface PaymentProvider {
 String name();
 PaymentProviderResult disburse(UUID sagaId,String borrowerId,BigDecimal amount,String currency);
 /**
  * Tra cứu kết quả lệnh chuyển tiền đã gửi theo idempotency key {@code sagaId}, dùng khi lần gọi trước mất kết quả
  * (worker chết, timeout). Rỗng nghĩa là provider không cho biết; caller phải giữ trạng thái đối soát, không được
  * gọi {@link #disburse} lại vì có thể chuyển tiền hai lần.
  */
 default Optional<PaymentProviderResult> lookup(UUID sagaId){return Optional.empty();}
 record PaymentProviderResult(boolean success,String reference,String errorCode,String errorMessage,boolean retryable){
  public static PaymentProviderResult success(String reference){return new PaymentProviderResult(true,reference,null,null,false);}
  public static PaymentProviderResult failure(String code,String message,boolean retryable){return new PaymentProviderResult(false,null,code,message,retryable);}
 }
}
