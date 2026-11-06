package com.finora.payment.messaging.producer;
import com.finora.common.messaging.KafkaEventPublisher;
import com.finora.common.messaging.KafkaOutgoingEvent;
import com.finora.common.messaging.KafkaPublishException;
import com.finora.payment.domain.outbox.entity.PaymentOutboxEvent;
import com.finora.payment.repository.PaymentOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
@Slf4j
@Component
@ConditionalOnProperty(prefix="finora.payment",name="scheduling-enabled",havingValue="true",matchIfMissing=true)
@RequiredArgsConstructor
public class PaymentOutboxPublisher{
 private final PaymentOutboxEventRepository paymentOutboxEventRepository;
 private final KafkaEventPublisher kafkaEventPublisher;
 private final Clock clock;
 private final Environment environment;
 @Value("${finora.payment.outbox-publish-timeout:10s}") private Duration publishTimeout;
 @Scheduled(fixedDelayString="${finora.payment.outbox-delay:3000}") public void publish(){
  paymentOutboxEventRepository.findDueIds(clock.instant(),PageRequest.of(0,50)).forEach(this::publishOne);
 }
 public void publishOne(Long id){
  PaymentOutboxEvent event=paymentOutboxEventRepository.findById(id).orElseThrow();
  try{String topic=topic(event.getEventType(),event.getEventVersion());
   // occurredAt là thời điểm tạo outbox như trước; Payment không lưu trace id nên không có header finora-trace-id.
   // Chờ broker có hạn: broker treo không được giữ worker vô thời hạn; consumer chống trùng theo eventId nên
   // gửi lại sau timeout là an toàn.
   kafkaEventPublisher.publish(new KafkaOutgoingEvent(topic,event.getAggregateId(),event.getEventId(),
    event.getEventType(),event.getEventVersion(),event.getCreatedAt(),event.getPayloadJson(),null),publishTimeout);
   event.published(clock.instant());
   paymentOutboxEventRepository.save(event);
  }catch(Exception ex){
   // Bị ngắt khi chờ broker: bộ gửi chung đã đặt lại cờ interrupt rồi ném KafkaPublishException; gửi lại sau như cũ.
   retryLater(event,ex);
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
 private void retryLater(PaymentOutboxEvent event,Exception ex){
  // Bộ gửi chung bọc lỗi trong KafkaPublishException: log tên lỗi bên trong như trước (TimeoutException...).
  Throwable failure=ex instanceof KafkaPublishException&&ex.getCause()!=null?ex.getCause():ex;
  log.warn("Publish Payment event thất bại, thử lại sau: eventId={}, type={}, error={}",
   event.getEventId(),event.getEventType(),failure.getClass().getSimpleName());
  event.failed("Kafka publish failed",clock.instant().plus(Duration.ofSeconds(10)));
  paymentOutboxEventRepository.save(event);
 }
}
