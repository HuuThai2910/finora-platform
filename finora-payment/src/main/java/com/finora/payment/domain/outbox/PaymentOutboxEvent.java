package com.finora.payment.domain.outbox;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="payment_outbox_events") @Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PaymentOutboxEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="event_id",nullable=false,unique=true,updatable=false) private UUID eventId;
    @Column(name="aggregate_id",nullable=false,length=100,updatable=false) private String aggregateId;
    @Column(name="event_type",nullable=false,length=100,updatable=false) private String eventType;
    @Column(name="event_version",nullable=false,updatable=false) private int eventVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="payload_json",nullable=false,columnDefinition="jsonb",updatable=false) private String payloadJson;
    @Column(nullable=false,length=20) private String status;
    @Column(name="attempt_count",nullable=false) private int attemptCount;
    @Column(name="available_at",nullable=false) private Instant availableAt;
    @Column(name="published_at") private Instant publishedAt;
    @Column(name="last_error",length=500) private String lastError;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    public static PaymentOutboxEvent pending(String aggregate,String type,String payload,Instant now){
        PaymentOutboxEvent e=new PaymentOutboxEvent();e.eventId=UUID.randomUUID();e.aggregateId=aggregate;
        e.eventType=type;e.eventVersion=1;e.payloadJson=payload;e.status="PENDING";e.availableAt=now;e.createdAt=now;e.updatedAt=now;return e;
    }
    public void published(Instant now){status="PUBLISHED";publishedAt=now;updatedAt=now;}
    public void failed(String message,Instant retryAt){attemptCount++;lastError=message==null?null:message.substring(0,Math.min(500,message.length()));availableAt=retryAt;updatedAt=retryAt;if(attemptCount>=10)status="DEAD_LETTER";}
}
