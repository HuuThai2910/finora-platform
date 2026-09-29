package com.finora.payment.messaging;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.payment.service.disbursement.PaymentDisbursementService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class DisbursementRequestedConsumer{
 private static final int EVENT_VERSION=2;
 private final ObjectMapper mapper;private final PaymentDisbursementService service;
 @KafkaListener(topics="${finora.payment.disbursement-requested-topic}")
 public void consume(@Payload String payload,@Header(name="finora-event-id",required=false)String id,
  @Header(name="finora-event-type",required=false)String type,@Header(name="finora-event-version",required=false)String version){
  try{KafkaEventEnvelope e=mapper.readValue(payload,KafkaEventEnvelope.class);require(e,id,type,version);
   service.request(e.eventId(),mapper.treeToValue(e.data(),DisbursementRequestedEventData.class));
  }catch(JsonProcessingException ex){throw new IllegalArgumentException("DisbursementRequested JSON không hợp lệ",ex);}
 }
 private void require(KafkaEventEnvelope e,String id,String type,String version){
  if(e==null||e.eventId()==null||e.data()==null||e.occurredAt()==null||e.version()!=EVENT_VERSION
   ||(id!=null&&!UUID.fromString(id).equals(e.eventId()))||(type!=null&&!"DisbursementRequested".equals(type))
   ||(version!=null&&Integer.parseInt(version)!=EVENT_VERSION))throw new IllegalArgumentException("DisbursementRequested envelope/header không hợp lệ");
 }
}
