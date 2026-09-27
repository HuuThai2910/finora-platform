package com.finora.payment.messaging;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.payment.domain.outbox.PaymentOutboxEvent;
import com.finora.payment.repository.PaymentOutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
@Component @RequiredArgsConstructor
public class PaymentOutboxPublisher{
 private final PaymentOutboxEventRepository repository;private final KafkaTemplate<String,String> kafka;
 private final ObjectMapper mapper;private final Clock clock;
 private final Environment environment;
 @Scheduled(fixedDelayString="${finora.payment.outbox-delay:3000}") public void publish(){
  repository.findDueIds(clock.instant(),PageRequest.of(0,50)).forEach(this::publishOne);
 }
 public void publishOne(Long id){
  PaymentOutboxEvent e=repository.findById(id).orElseThrow();
  try{JsonNode data=mapper.readTree(e.getPayloadJson());String envelope=mapper.writeValueAsString(
    new KafkaEventEnvelope(e.getEventId(),e.getCreatedAt().toString(),e.getEventVersion(),data));
   String topic="DisbursementCompleted".equals(e.getEventType())
    ?environment.getProperty("finora.payment.disbursement-completed-topic","finora.payment.disbursement-completed")
    :environment.getProperty("finora.payment.disbursement-failed-topic","finora.payment.disbursement-failed");
   ProducerRecord<String,String> record=new ProducerRecord<>(topic,e.getAggregateId(),envelope);
   record.headers().add("finora-event-id",e.getEventId().toString().getBytes(StandardCharsets.UTF_8));
   record.headers().add("finora-event-type",e.getEventType().getBytes(StandardCharsets.UTF_8));
   record.headers().add("finora-event-version",Integer.toString(e.getEventVersion()).getBytes(StandardCharsets.UTF_8));
   kafka.send(record).get();e.published(clock.instant());repository.save(e);
  }catch(Exception ex){e.failed("Kafka publish failed",clock.instant().plus(Duration.ofSeconds(10)));repository.save(e);}
 }
}
