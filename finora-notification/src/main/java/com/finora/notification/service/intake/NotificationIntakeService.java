package com.finora.notification.service.intake;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.domain.DeferredEvent;
import com.finora.notification.domain.ProcessedEvent;
import com.finora.notification.repository.DeferredEventRepository;
import com.finora.notification.repository.ProcessedEventRepository;
import com.finora.notification.service.delivery.NotificationDeliveryService;
import com.finora.notification.service.delivery.NotificationDraft;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cửa nhận event duy nhất của Notification (F09, D60): idempotent theo {@code eventId}, chọn handler theo loại/version,
 * lưu thông báo + dấu đã xử lý trong cùng một transaction.
 * <pre>
 * Transaction (consumer hoặc worker):
 *   processed_events có eventId? → bỏ qua (Kafka giao lại)
 *   handler cập nhật bảng tra người nhận / lịch nhắc
 *   ├─ đủ người nhận → in_app_notifications + push_deliveries + processed_events → cùng commit
 *   └─ thiếu người nhận → notification_deferred_events (chưa ghi processed) → worker thử lại
 * Không gọi mạng trong transaction; push được worker gửi sau. Lỗi ở đây không ảnh hưởng service nguồn.
 * </pre>
 */
@Slf4j
@Service
public class NotificationIntakeService {

    private final Map<EventKey, InboundEventHandler> handlers = new HashMap<>();
    private final Set<String> knownTypes = new HashSet<>();
    private final ProcessedEventRepository processed;
    private final DeferredEventRepository deferred;
    private final NotificationDeliveryService deliveries;
    private final EventPayloads payloads;
    private final NotificationProperties.Deferred deferredPolicy;
    private final Clock clock;

    public NotificationIntakeService(List<InboundEventHandler> handlerBeans, ProcessedEventRepository processed,
            DeferredEventRepository deferred, NotificationDeliveryService deliveries, EventPayloads payloads,
            NotificationProperties properties, Clock clock) {
        for (InboundEventHandler handler : handlerBeans) {
            for (EventKey key : handler.keys()) {
                if (handlers.putIfAbsent(key, handler) != null) {
                    throw new IllegalStateException("Hai handler cùng nhận " + key);
                }
                knownTypes.add(key.type());
            }
        }
        this.processed = processed;
        this.deferred = deferred;
        this.deliveries = deliveries;
        this.payloads = payloads;
        this.deferredPolicy = properties.deferred();
        this.clock = clock;
    }

    /** Event mới từ Kafka. */
    @Transactional
    public IntakeResult accept(InboundEvent event) {
        if (processed.existsByEventId(event.eventId())) {
            log.debug("Bỏ qua event đã xử lý: eventId={}, type={}", event.eventId(), event.eventType());
            return IntakeResult.DUPLICATE;
        }
        InboundEventHandler handler = handlers.get(event.key());
        if (handler == null) {
            if (knownTypes.contains(event.eventType())) {
                log.warn("Bỏ qua version event không còn hỗ trợ: eventId={}, type={}, version={}",
                        event.eventId(), event.eventType(), event.version());
                return IntakeResult.UNSUPPORTED_VERSION;
            }
            throw new IllegalArgumentException("Notification chưa xử lý loại event " + event.eventType());
        }
        Instant now = clock.instant();
        Optional<DeferredEvent> waiting = deferred.findByEventId(event.eventId());
        return switch (handler.handle(event)) {
            case HandlerOutcome.AwaitRecipients await -> {
                if (waiting.isEmpty()) {
                    deferred.save(DeferredEvent.waiting(event.eventId(), event.eventType(), event.version(),
                            event.topic(), event.occurredAt(), payloads.write(event), await.reason(), now,
                            deferredPolicy.retryInterval(), deferredPolicy.maxWait()));
                    log.info("Event chờ người nhận: eventId={}, type={}, reason={}",
                            event.eventId(), event.eventType(), await.reason());
                }
                yield IntakeResult.DEFERRED;
            }
            case HandlerOutcome.Deliver deliver -> finish(event, deliver.drafts(), ProcessedEvent.Outcome.DELIVERED,
                    now, waiting.orElse(null), false);
            case HandlerOutcome.Filtered filtered -> {
                log.debug("Event không cần thông báo: eventId={}, type={}, reason={}",
                        event.eventId(), event.eventType(), filtered.reason());
                yield finish(event, List.of(), ProcessedEvent.Outcome.FILTERED, now, waiting.orElse(null), false);
            }
        };
    }

    /**
     * Worker thử lại một event đang chờ. Khóa dòng bằng SKIP LOCKED: hai worker không xử lý cùng event. Hết thời gian
     * chờ thì gửi cho người đã biết, ghi {@code EXPIRED_PARTIAL} và giữ dòng ở trạng thái EXPIRED cho vận hành.
     */
    @Transactional
    public Optional<IntakeResult> retryDeferred(Long deferredId) {
        DeferredEvent row = deferred.lockWaiting(deferredId).orElse(null);
        if (row == null) return Optional.empty();
        if (processed.existsByEventId(row.getEventId())) {
            deferred.delete(row);
            return Optional.of(IntakeResult.DUPLICATE);
        }
        InboundEvent event = payloads.withData(new InboundEvent(row.getEventId(), row.getEventType(),
                row.getEventVersion(), row.getOccurredAt(), row.getSourceTopic(), null), row.getPayloadJson());
        InboundEventHandler handler = handlers.get(event.key());
        Instant now = clock.instant();
        if (handler == null) {
            row.expire(now);
            log.error("Event chờ không còn handler, chuyển EXPIRED: eventId={}, type={}",
                    row.getEventId(), row.getEventType());
            return Optional.of(IntakeResult.UNSUPPORTED_VERSION);
        }
        IntakeResult result = switch (handler.handle(event)) {
            case HandlerOutcome.AwaitRecipients await -> {
                if (row.isExpired(now)) {
                    IntakeResult expired = finish(event, await.knownDrafts(), ProcessedEvent.Outcome.EXPIRED_PARTIAL,
                            now, row, true);
                    log.warn("Hết thời gian chờ người nhận: eventId={}, type={}, reason={}, delivered={}",
                            row.getEventId(), row.getEventType(), await.reason(), await.knownDrafts().size());
                    yield expired;
                }
                row.scheduleRetry(await.reason(), now, deferredPolicy.retryInterval(), deferredPolicy.maxInterval());
                yield IntakeResult.DEFERRED;
            }
            case HandlerOutcome.Deliver deliver -> finish(event, deliver.drafts(), ProcessedEvent.Outcome.DELIVERED,
                    now, row, false);
            case HandlerOutcome.Filtered filtered -> finish(event, List.of(), ProcessedEvent.Outcome.FILTERED, now,
                    row, false);
        };
        return Optional.of(result);
    }

    /** Event chờ bị lỗi contract khi xử lý lại (không nên xảy ra): dừng thử, giữ dòng EXPIRED để điều tra. */
    @Transactional
    public void abandonDeferred(Long deferredId, String reason) {
        deferred.lockWaiting(deferredId).ifPresent(row -> {
            row.expire(clock.instant());
            log.error("Dừng thử event chờ vì lỗi contract: eventId={}, type={}, reason={}",
                    row.getEventId(), row.getEventType(), reason);
        });
    }

    private IntakeResult finish(InboundEvent event, List<NotificationDraft> drafts, ProcessedEvent.Outcome outcome,
            Instant now, DeferredEvent waiting, boolean keepWaitingRowAsExpired) {
        int created = drafts.isEmpty() ? 0 : deliveries.deliver(event.eventId(), event.occurredAt(), drafts);
        ProcessedEvent.Outcome finalOutcome = outcome == ProcessedEvent.Outcome.DELIVERED && drafts.isEmpty()
                ? ProcessedEvent.Outcome.NO_RECIPIENT : outcome;
        processed.save(ProcessedEvent.create(event.eventId(), event.eventType(), event.version(), event.topic(),
                finalOutcome, created, now));
        if (waiting != null) {
            if (keepWaitingRowAsExpired) waiting.expire(now);
            else deferred.delete(waiting);
        }
        // Consumer/worker log topic/offset/kết quả ở mức INFO; ở đây chỉ thêm số thông báo đã tạo.
        log.debug("Đã xử lý event thông báo: eventId={}, type={}, topic={}, outcome={}, created={}",
                event.eventId(), event.eventType(), event.topic(), finalOutcome, created);
        return switch (finalOutcome) {
            case DELIVERED -> IntakeResult.DELIVERED;
            case NO_RECIPIENT -> IntakeResult.NO_RECIPIENT;
            case FILTERED -> IntakeResult.FILTERED;
            case EXPIRED_PARTIAL -> IntakeResult.EXPIRED_PARTIAL;
        };
    }
}
