package com.finora.payment.domain.messaging;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity @Table(name="payment_processed_events")
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PaymentProcessedEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="event_id",nullable=false,unique=true,updatable=false) private UUID eventId;
    @Column(name="event_type",nullable=false,length=100,updatable=false) private String eventType;
    @Column(name="aggregate_id",nullable=false,length=100,updatable=false) private String aggregateId;
    @Column(name="processed_at",nullable=false,updatable=false) private Instant processedAt;
    public static PaymentProcessedEvent of(UUID id,String type,String aggregate,Instant now){
        PaymentProcessedEvent e=new PaymentProcessedEvent(); e.eventId=id;e.eventType=type;e.aggregateId=aggregate;e.processedAt=now;return e;
    }
}

