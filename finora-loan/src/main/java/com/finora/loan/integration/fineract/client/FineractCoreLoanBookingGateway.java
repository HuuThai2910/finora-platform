package com.finora.loan.integration.fineract.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.config.FineractBookingProperties;
import com.finora.loan.integration.fineract.mapper.FineractStrategyResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Adapter Fineract 1.15; externalId được dùng để reconcile trước mọi create/retry. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="finora.fineract.booking-provider",havingValue="fineract")
public class FineractCoreLoanBookingGateway implements CoreLoanBookingGateway{
 private final RestClient fineractRestClient;
 private final FineractRequestExecutor executor;
 private final FineractBookingProperties properties;
 private final Clock clock;

 /**
  * Ghi khoản vay vào Fineract theo ngày giải ngân thực tế. Hợp đồng quy định thời hạn vay và lịch chính thức tính từ
  * ngày giải ngân thực tế, nên mọi ngày core của lần ghi này dùng chung một {@code bookingDate} (ngày nghiệp vụ Việt
  * Nam). Gửi ngày dự kiến lúc nộp hồ sơ sẽ bị Fineract từ chối khi gọi vốn/ký kéo dài qua ngày đó.
  */
 @Override public CoreLoanBookingResult bookAndDisburse(CoreLoanBookingCommand c){
  properties.requireConfigured();
  LocalDate bookingDate=LocalDate.now(clock);
  long clientId=ensureClient(c.borrowerId(),bookingDate);
  long loanId=ensureLoan(clientId,c,bookingDate);
  JsonNode loan=readLoan(loanId,"read-loan");
  if(!loan.path("status").path("active").asBoolean(false)){
   if(loan.path("status").path("pendingApproval").asBoolean(false))approve(loanId,c,bookingDate);
   loan=readLoan(loanId,"read-approved-loan");
   if(!loan.path("status").path("active").asBoolean(false))disburse(loanId,c,bookingDate);
  }
  // Lần đọc cuối (đã duyệt hoặc đã giải ngân) có lịch theo ngày giải ngân thực tế bookingDate, nên ngày đáo hạn và kỳ đầu
  // không đổi sau bước disburse cùng ngày; Investment dùng ngày đáo hạn cho vị thế của nhà đầu tư (B34), Notification dùng
  // kỳ đầu để nhắc lịch từ kỳ 1 (P3, D76). Cả ba lấy từ cùng một lần đọc, FINORA không tự tính lịch.
  NextDue next=nextDue(loan.path("repaymentSchedule").path("periods"));
  return new CoreLoanBookingResult(loanId,date(loan.path("timeline").path("expectedMaturityDate")),
    next==null?null:next.date(),next==null?null:next.amount());
 }

 /** Đọc khoản vay kèm lịch trả nợ: trạng thái, ngày đáo hạn và kỳ đầu cùng một snapshot của Fineract. */
 private JsonNode readLoan(long loanId,String operation){
  return get("/loans/"+loanId+"?associations=repaymentSchedule",operation);
 }

 /**
  * Kỳ đầu tiên còn phải trả trong lịch Fineract (P3, D76): dòng có số kỳ (bỏ dòng giải ngân), còn dư nợ của kỳ
  * ({@code totalOutstandingForPeriod > 0}), ngày đến hạn sớm nhất. Lịch thiếu hoặc không đọc được thì trả {@code null}:
  * field chỉ phục vụ thông báo, không được làm hỏng lần ghi core đã thành công.
  */
 static NextDue nextDue(JsonNode periods){
  if(periods==null||!periods.isArray())return null;
  NextDue next=null;
  for(JsonNode period:periods){
   if(!period.hasNonNull("period"))continue;
   LocalDate due=date(period.path("dueDate"));
   JsonNode outstanding=period.get("totalOutstandingForPeriod");
   if(due==null||outstanding==null||!outstanding.isNumber()||outstanding.decimalValue().signum()<=0)continue;
   if(next==null||due.isBefore(next.date()))next=new NextDue(due,outstanding.decimalValue());
  }
  return next;
 }

 record NextDue(LocalDate date,BigDecimal amount){}

 private static LocalDate date(JsonNode value){
  if(!value.isArray()||value.size()<3)return null;
  return LocalDate.of(value.get(0).asInt(),value.get(1).asInt(),value.get(2).asInt());
 }

 private long ensureClient(String borrowerId,LocalDate bookingDate){
  String external="FINORA-BORROWER-"+borrowerId;
  Long existing=find("/clients?externalId="+external);
  if(existing!=null)return existing;
  Map<String,Object> body=new LinkedHashMap<>();body.put("officeId",properties.officeId());
  body.put("firstname","FINORA");body.put("lastname","Borrower");body.put("externalId",external);
  body.put("legalFormId",properties.clientLegalFormId());
  body.put("active",true);body.put("activationDate",bookingDate.toString());common(body);
  return resource(post("/clients",body,"create-client"));
 }
 private long ensureLoan(long clientId,CoreLoanBookingCommand c,LocalDate bookingDate){
  String external=c.contractNumber();Long existing=find("/loans?externalId="+external);if(existing!=null)return existing;
  Map<String,Object> body=new LinkedHashMap<>();body.put("clientId",clientId);body.put("productId",c.productId());
  body.put("externalId",external);body.put("principal",c.principal());body.put("loanTermFrequency",c.termMonths());
  body.put("loanTermFrequencyType",2);body.put("loanType","individual");body.put("numberOfRepayments",c.termMonths());
  body.put("repaymentEvery",1);body.put("repaymentFrequencyType",2);body.put("interestRatePerPeriod",c.annualRate());
  body.put("amortizationType",1);body.put("interestType",0);
  body.put("interestCalculationPeriodType",FineractStrategyResolver.interestCalculationPeriodType(c.coreConfigVersion()));
  body.put("transactionProcessingStrategyCode",FineractStrategyResolver.strategy(c.coreConfigVersion()));
  body.put("expectedDisbursementDate",bookingDate.toString());body.put("submittedOnDate",bookingDate.toString());common(body);
  return resource(post("/loans",body,"create-loan"));
 }
 private void approve(long id,CoreLoanBookingCommand c,LocalDate bookingDate){Map<String,Object>b=new LinkedHashMap<>();
  b.put("approvedOnDate",bookingDate.toString());b.put("approvedLoanAmount",c.principal());
  b.put("expectedDisbursementDate",bookingDate.toString());common(b);post("/loans/"+id+"?command=approve",b,"approve-loan");}
 private void disburse(long id,CoreLoanBookingCommand c,LocalDate bookingDate){Map<String,Object>b=new LinkedHashMap<>();
  b.put("actualDisbursementDate",bookingDate.toString());b.put("transactionAmount",c.principal());
  b.put("paymentTypeId",properties.paymentTypeId());b.put("note","FINORA "+c.paymentReference());common(b);
  post("/loans/"+id+"?command=disburse",b,"disburse-loan");}
 private void common(Map<String,Object>b){b.put("dateFormat",properties.dateFormat());b.put("locale",properties.locale());}
 private Long find(String uri){JsonNode n=get(uri,"reconcile");JsonNode items=n.path("pageItems");
  if(items.isArray()&&items.size()>0)return items.get(0).path("id").asLong();return null;}
 private JsonNode get(String uri,String operation){return executor.execute(FineractCallGroup.BOOKING,operation,()->
  fineractRestClient.get().uri(uri).headers(executor::authenticate).retrieve().body(JsonNode.class));}
 private JsonNode post(String uri,Object body,String operation){return executor.execute(FineractCallGroup.BOOKING,operation,()->
  fineractRestClient.post().uri(uri).headers(executor::authenticate).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));}
 private long resource(JsonNode n){long id=n==null?0:n.path("resourceId").asLong();if(id<=0)throw new FineractIntegrationException(
  "FINERACT_RESPONSE_INVALID","Fineract không trả resourceId",false,null);return id;}
}
