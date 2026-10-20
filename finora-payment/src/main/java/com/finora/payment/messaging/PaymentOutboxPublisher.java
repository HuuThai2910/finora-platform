package com.finora.payment.messaging;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.payment.domain.outbox.PaymentOutboxEvent;
import com.finora.payment.repository.PaymentOutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
@Slf4j
@Component
@ConditionalOnProperty(prefix="finora.payment",name="scheduling-enabled",havingValue="true",matchIfMissing=true)
@RequiredArgsConstructor
public class PaymentOutboxPublisher{
 private final PaymentOutboxEventRepository repository;private final KafkaTemplate<String,String> kafka;
 private final ObjectMapper mapper;private final Clock clock;
 private final Environment environment;
 @Value("${finora.payment.outbox-publish-timeout:10s}") private Duration publishTimeout;
 @Scheduled(fixedDelayString="${finora.payment.outbox-delay:3000}") public void publish(){
  repository.findDueIds(clock.instant(),PageRequest.of(0,50)).forEach(this::publishOne);
 }
 public void publishOne(Long id){
  PaymentOutboxEvent e=repository.findById(id).orElseThrow();
  try{JsonNode data=mapper.readTree(e.getPayloadJson());String envelope=mapper.writeValueAsString(
    new KafkaEventEnvelope(e.getEventId(),e.getCreatedAt().toString(),e.getEventVersion(),data));
   String topic=topic(e.getEventType(),e.getEventVersion());
   ProducerRecord<String,String> record=new ProducerRecord<>(topic,e.getAggregateId(),envelope);
   record.headers().add("finora-event-id",e.getEventId().toString().getBytes(StandardCharsets.UTF_8));
   record.headers().add("finora-event-type",e.getEventType().getBytes(StandardCharsets.UTF_8));
   record.headers().add("finora-event-version",Integer.toString(e.getEventVersion()).getBytes(StandardCharsets.UTF_8));
   // Chờ broker có hạn: broker treo không được giữ worker vô thời hạn; consumer chống trùng theo eventId nên
   // gửi lại sau timeout là an toàn.
   kafka.send(record).get(publishTimeout.toMillis(),TimeUnit.MILLISECONDS);e.published(clock.instant());repository.save(e);
  }catch(InterruptedException ex){
   Thread.currentThread().interrupt();
   retryLater(e,ex);
  }catch(Exception ex){
   retryLater(e,ex);
  }
 }
 /**
  * Allowlist route theo đúng loại + version event của Payment (mọi event hiện ở v1). Event/version chưa khai báo không
  * được gửi (thử lại rồi {@code DEAD_LETTER}) thay vì rơi vào topic đoán. {@code RepaymentCollected}/
  * {@code RepaymentCancelled} là P5 (W3F, D77), consumer Notification.
  */
 String topic(String eventType,int eventVersion){
  if(eventVersion!=1)throw new IllegalArgumentException("Payment event chưa có Kafka route: "+eventType+" v"+eventVersion);
  return switch(eventType){
   case "DisbursementCompleted" -> environment.getProperty("finora.payment.disbursement-completed-topic","finora.payment.disbursement-completed");
   case "DisbursementFailed" -> environment.getProperty("finora.payment.disbursement-failed-topic","finora.payment.disbursement-failed");
   case "RepaymentDistributed" -> environment.getProperty("finora.payment.repayment-distributed-topic","finora.payment.repayment-distributed");
   case "RepaymentCollected" -> environment.getProperty("finora.payment.repayment-collected-topic","finora.payment.repayment-collected");
   case "RepaymentCancelled" -> environment.getProperty("finora.payment.repayment-cancelled-topic","finora.payment.repayment-cancelled");
   case "WithdrawalCompleted" -> environment.getProperty("finora.payment.withdrawal-completed-topic","finora.payment.withdrawal-completed");
   case "WithdrawalFailed" -> environment.getProperty("finora.payment.withdrawal-failed-topic","finora.payment.withdrawal-failed");
   default -> throw new IllegalArgumentException("Payment event chưa có Kafka route: "+eventType+" v"+eventVersion);
  };
 }
 private void retryLater(PaymentOutboxEvent e,Exception ex){
  log.warn("Publish Payment event thất bại, thử lại sau: eventId={}, type={}, error={}",
   e.getEventId(),e.getEventType(),ex.getClass().getSimpleName());
  e.failed("Kafka publish failed",clock.instant().plus(Duration.ofSeconds(10)));repository.save(e);
 }
}
