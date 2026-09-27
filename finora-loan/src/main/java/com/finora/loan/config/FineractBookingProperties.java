package com.finora.loan.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix="finora.fineract.booking")
public record FineractBookingProperties(Long officeId,Long paymentTypeId,String transactionProcessingStrategyCode,
                                        String locale,String dateFormat){
 public FineractBookingProperties{
  locale=locale==null||locale.isBlank()?"en":locale.trim();
  dateFormat=dateFormat==null||dateFormat.isBlank()?"yyyy-MM-dd":dateFormat.trim();
  transactionProcessingStrategyCode=transactionProcessingStrategyCode==null||transactionProcessingStrategyCode.isBlank()
   ?"mifos-standard-strategy":transactionProcessingStrategyCode.trim();
 }
 public void requireConfigured(){
  if(officeId==null||officeId<=0||paymentTypeId==null||paymentTypeId<=0)
   throw new IllegalStateException("FINERACT_OFFICE_ID và FINERACT_DISBURSEMENT_PAYMENT_TYPE_ID phải được cấu hình");
 }
}
