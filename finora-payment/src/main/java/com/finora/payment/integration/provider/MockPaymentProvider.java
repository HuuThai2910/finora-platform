package com.finora.payment.integration.provider;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
@Component
@ConditionalOnProperty(name="finora.payment.disbursement.provider",havingValue="mock",matchIfMissing=true)
public class MockPaymentProvider implements PaymentProvider{
 private final String outcome;
 public MockPaymentProvider(@Value("${finora.payment.mock.outcome:success}") String outcome){this.outcome=outcome;}
 public String name(){return "MOCK";}
 public PaymentProviderResult disburse(UUID sagaId,String borrowerId,BigDecimal amount,String currency){
  if("retryable-failure".equalsIgnoreCase(outcome))
   return PaymentProviderResult.failure("MOCK_TEMPORARY_FAILURE","Lỗi mock có thể retry",true);
  if("permanent-failure".equalsIgnoreCase(outcome))
   return PaymentProviderResult.failure("MOCK_REJECTED","Lỗi mock cuối cùng",false);
  return PaymentProviderResult.success("MOCK-"+sagaId);
 }
 /** Mock xử lý tất định theo sagaId nên tra cứu trả đúng kết quả lần gọi trước; lỗi tạm thời coi như chưa biết. */
 @Override public Optional<PaymentProviderResult> lookup(UUID sagaId){
  if("retryable-failure".equalsIgnoreCase(outcome))return Optional.empty();
  return Optional.of(disburse(sagaId,null,null,null));
 }
}
