package com.finora.loan.integration.fineract.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.config.FineractBookingProperties;
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

 @Override public CoreLoanBookingResult bookAndDisburse(CoreLoanBookingCommand c){
  properties.requireConfigured();
  long clientId=ensureClient(c.borrowerId());
  long loanId=ensureLoan(clientId,c);
  JsonNode loan=get("/loans/"+loanId,"read-loan");
  if(!loan.path("status").path("active").asBoolean(false)){
   if(loan.path("status").path("pendingApproval").asBoolean(false))approve(loanId,c);
   loan=get("/loans/"+loanId,"read-approved-loan");
   if(!loan.path("status").path("active").asBoolean(false))disburse(loanId,c);
  }
  return new CoreLoanBookingResult(loanId);
 }

 private long ensureClient(String borrowerId){
  String external="FINORA-BORROWER-"+borrowerId;
  Long existing=find("/clients?externalId="+external);
  if(existing!=null)return existing;
  Map<String,Object> body=new LinkedHashMap<>();body.put("officeId",properties.officeId());
  body.put("firstname","FINORA");body.put("lastname","Borrower");body.put("externalId",external);
  body.put("active",true);body.put("activationDate",LocalDate.now(clock).toString());common(body);
  return resource(post("/clients",body,"create-client"));
 }
 private long ensureLoan(long clientId,CoreLoanBookingCommand c){
  String external=c.contractNumber();Long existing=find("/loans?externalId="+external);if(existing!=null)return existing;
  Map<String,Object> body=new LinkedHashMap<>();body.put("clientId",clientId);body.put("productId",c.productId());
  body.put("externalId",external);body.put("principal",c.principal());body.put("loanTermFrequency",c.termMonths());
  body.put("loanTermFrequencyType",2);body.put("loanType","individual");body.put("numberOfRepayments",c.termMonths());
  body.put("repaymentEvery",1);body.put("repaymentFrequencyType",2);body.put("interestRatePerPeriod",c.annualRate());
  body.put("amortizationType",1);body.put("interestType",0);body.put("interestCalculationPeriodType",0);
  body.put("transactionProcessingStrategyCode",properties.transactionProcessingStrategyCode());
  body.put("expectedDisbursementDate",c.disbursementDate().toString());body.put("submittedOnDate",LocalDate.now(clock).toString());common(body);
  return resource(post("/loans",body,"create-loan"));
 }
 private void approve(long id,CoreLoanBookingCommand c){Map<String,Object>b=new LinkedHashMap<>();
  b.put("approvedOnDate",LocalDate.now(clock).toString());b.put("approvedLoanAmount",c.principal());
  b.put("expectedDisbursementDate",c.disbursementDate().toString());common(b);post("/loans/"+id+"?command=approve",b,"approve-loan");}
 private void disburse(long id,CoreLoanBookingCommand c){Map<String,Object>b=new LinkedHashMap<>();
  b.put("actualDisbursementDate",LocalDate.now(clock).toString());b.put("transactionAmount",c.principal());
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
