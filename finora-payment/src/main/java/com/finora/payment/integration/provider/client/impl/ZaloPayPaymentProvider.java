package com.finora.payment.integration.provider.client.impl;
import com.finora.payment.integration.provider.client.PaymentProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
/** Adapter fail-closed cho tới khi ZaloPay cấp AppId/key và quyền disbursement sandbox. */
@Component
@ConditionalOnProperty(name="finora.payment.disbursement.provider",havingValue="zalopay")
public class ZaloPayPaymentProvider implements PaymentProvider{
 public String name(){return "ZALOPAY";}
 public PaymentProviderResult disburse(UUID sagaId,String borrowerId,BigDecimal amount,String currency){
  return PaymentProviderResult.failure("ZALOPAY_NOT_CONFIGURED","Chưa có thông tin merchant/quyền disbursement sandbox",false);
 }
}
