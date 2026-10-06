package com.finora.loan.config;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix="finora.fineract.booking")
public record FineractBookingProperties(
        Long officeId,
        Long paymentTypeId,
        Long clientLegalFormId,
        String locale,
        String dateFormat
){
 public FineractBookingProperties{
  clientLegalFormId=clientLegalFormId==null||clientLegalFormId<=0?1L:clientLegalFormId;
  locale=locale==null||locale.isBlank()?"en":locale.trim();
  dateFormat=dateFormat==null||dateFormat.isBlank()?"yyyy-MM-dd":dateFormat.trim();
 }
 public void requireConfigured(){
  if(officeId==null||officeId<=0||paymentTypeId==null||paymentTypeId<=0)
   throw new IllegalStateException("FINERACT_OFFICE_ID và FINERACT_DISBURSEMENT_PAYMENT_TYPE_ID phải được cấu hình");
 }
}
