package com.finora.investment.messaging.consumer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.messaging.event.KafkaEventEnvelope;
import com.finora.investment.messaging.event.LoanDisbursedEventData;
import com.finora.investment.service.messaging.LoanDisbursedEventHandler;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class LoanDisbursedConsumer{
 private final ObjectMapper mapper;private final LoanDisbursedEventHandler handler;
 @KafkaListener(topics="${finora.investment.kafka.loan-disbursed-topic}")
 public void consume(@Payload String payload,@Header(name="finora-event-id",required=false)String id,
  @Header(name="finora-event-type",required=false)String type,@Header(name="finora-event-version",required=false)String version){
  try{KafkaEventEnvelope e=mapper.readValue(payload,KafkaEventEnvelope.class);require(e,id,type,version);
   handler.handle(e.eventId(),Instant.parse(e.occurredAt()),mapper.treeToValue(e.data(),LoanDisbursedEventData.class));
  }catch(JsonProcessingException ex){throw new IllegalArgumentException("LoanDisbursed JSON không hợp lệ",ex);}
 }
 private void require(KafkaEventEnvelope e,String id,String type,String version){
  if(e==null||e.eventId()==null||e.data()==null||e.occurredAt()==null||e.version()!=1
   ||(id!=null&&!UUID.fromString(id).equals(e.eventId()))||(type!=null&&!"LoanDisbursed".equals(type))
   ||(version!=null&&Integer.parseInt(version)!=1))throw new IllegalArgumentException("LoanDisbursed envelope/header không hợp lệ");
 }
}

