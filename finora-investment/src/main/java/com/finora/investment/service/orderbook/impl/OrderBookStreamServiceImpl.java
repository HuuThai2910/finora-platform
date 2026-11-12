package com.finora.investment.service.orderbook.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.config.OrderBookWebSocketProperties;
import com.finora.investment.dto.response.OrderBookSnapshotResponse;
import com.finora.investment.dto.response.OrderBookStreamMessage;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.service.orderbook.OrderBookChangedEvent;
import com.finora.investment.service.orderbook.OrderBookQueryService;
import com.finora.investment.service.orderbook.OrderBookStreamService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Đẩy sổ lệnh theo thời gian thực qua WebSocket thuần của Spring (không STOMP).
 *
 * <p>Kênh chỉ chảy một chiều server → client; đặt/huỷ lệnh vẫn qua REST có idempotency. Tin nhắn client gửi lên chỉ
 * được coi là tín hiệu còn sống, không có lệnh nào đi qua WebSocket. Xác thực bằng vé dùng một lần lúc bắt tay
 * ({@code OrderBookStreamTicketService}), không dùng cookie hay header.</p>
 *
 * <p>Mỗi lần đẩy là <b>cả ảnh chụp</b> (độ sâu 20 mức mỗi phía, 20 giao dịch gần nhất), không phải phần chênh lệch.
 * Sổ của một khoản vay P2P nhỏ, nên gửi cả ảnh rẻ hơn nhiều so với cái giá phải giữ đúng thứ tự và bù gói bị mất của
 * luồng chênh lệch. Client giữ ảnh có {@code sequence} lớn nhất.</p>
 *
 * <p>Giữ kết nối: mỗi {@code heartbeat-interval} gửi khung ping (trình duyệt và WebSocket của React Native tự trả
 * pong). Quá {@code idle-timeout} không nhận pong hay tin nào thì đóng phiên đó (phiên chết, ví dụ điện thoại mất
 * sóng mà TCP chưa báo). Phiên mở quá {@code max-session-duration} bị đóng với mã {@code 4000} để client lấy vé mới.
 * Mọi lần gửi đi qua {@link ConcurrentWebSocketSessionDecorator} vì luồng đẩy, luồng nhịp và luồng bắt tay cùng gửi
 * vào một phiên; client quá chậm (vượt giới hạn thời gian/bộ đệm) bị đóng và tự nối lại.</p>
 *
 * <p>Giới hạn: danh sách phiên nằm trong bộ nhớ của từng instance. Chạy nhiều instance thì client chỉ nhận cập nhật
 * từ lệnh đi qua đúng instance của mình — cần kênh phát chung (Kafka hoặc PostgreSQL LISTEN/NOTIFY) khi mở rộng. Bản
 * hiện tại chạy một instance.</p>
 */
@Slf4j
@Service
public class OrderBookStreamServiceImpl implements OrderBookStreamService {

    /** Sổ không tồn tại (mã đóng riêng của ứng dụng, dải 4000–4999). Client không nối lại. */
    public static final CloseStatus LISTING_NOT_FOUND = new CloseStatus(4404, "LISTING_NOT_FOUND");
    /** Phiên mở quá thời hạn; client xin vé mới rồi nối lại ngay. */
    public static final CloseStatus SESSION_EXPIRED = new CloseStatus(4000, "SESSION_EXPIRED");

    private static final ByteBuffer PING_PAYLOAD = ByteBuffer.wrap("finora".getBytes(StandardCharsets.US_ASCII));

    private final OrderBookQueryService orderBookQueryService;
    private final OrderBookWebSocketProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Phiên theo sổ, để đẩy ảnh của một sổ. */
    private final Map<Long, Set<Subscriber>> subscribers = new ConcurrentHashMap<>();
    /** Phiên theo mã phiên, để gỡ và ghi nhận tín hiệu sống. */
    private final Map<String, Subscriber> sessions = new ConcurrentHashMap<>();

    /** Sổ đang chờ đẩy ảnh mới; gom nhiều thay đổi liên tiếp thành một lần đẩy. */
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();

    /**
     * Một luồng duy nhất dựng và gửi ảnh chụp: ảnh của cùng một sổ đi ra theo đúng thứ tự commit, và việc chờ client
     * chậm không chiếm luồng xử lý request.
     */
    private final ExecutorService broadcaster = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "order-book-broadcaster");
        thread.setDaemon(true);
        return thread;
    });

    public OrderBookStreamServiceImpl(
            OrderBookQueryService orderBookQueryService,
            OrderBookWebSocketProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.orderBookQueryService = orderBookQueryService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void subscribe(Long listingId, WebSocketSession session) {
        WebSocketSession concurrent = new ConcurrentWebSocketSessionDecorator(session,
                Math.toIntExact(properties.sendTimeLimit().toMillis()), properties.sendBufferSizeLimit());
        Subscriber subscriber = new Subscriber(listingId, concurrent, clock.instant());
        // Ghi danh trước rồi mới dựng ảnh đầu tiên: thay đổi commit giữa hai bước vẫn được đẩy, client giữ ảnh có
        // sequence lớn hơn nên hai ảnh đến lệch thứ tự cũng không sao.
        sessions.put(session.getId(), subscriber);
        subscribers.compute(listingId, (id, listeners) -> {
            Set<Subscriber> set = listeners == null ? ConcurrentHashMap.newKeySet() : listeners;
            set.add(subscriber);
            return set;
        });

        OrderBookSnapshotResponse initial;
        try {
            initial = orderBookQueryService.snapshot(listingId);
        } catch (InvestmentDomainException failure) {
            close(subscriber, failure.getKind() == InvestmentDomainException.Kind.NOT_FOUND
                    ? LISTING_NOT_FOUND : CloseStatus.SERVER_ERROR);
            return;
        } catch (RuntimeException failure) {
            log.warn("Không dựng được ảnh sổ lệnh đầu tiên: listingId={}, exceptionType={}",
                    listingId, failure.getClass().getName());
            close(subscriber, CloseStatus.SERVER_ERROR);
            return;
        }
        TextMessage message = toMessage(initial);
        if (message != null) {
            send(subscriber, message);
        }
    }

    @Override
    public void unsubscribe(WebSocketSession session) {
        Subscriber subscriber = sessions.remove(session.getId());
        if (subscriber != null) {
            subscribers.computeIfPresent(subscriber.listingId, (id, listeners) -> {
                listeners.remove(subscriber);
                return listeners.isEmpty() ? null : listeners;
            });
        }
    }

    @Override
    public void touch(WebSocketSession session) {
        Subscriber subscriber = sessions.get(session.getId());
        if (subscriber != null) {
            subscriber.lastSeen = clock.instant();
        }
    }

    @Override
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookChanged(OrderBookChangedEvent event) {
        Long listingId = event.listingId();
        if (!subscribers.containsKey(listingId) || !pending.add(listingId)) {
            return;
        }
        broadcaster.execute(() -> {
            // Bỏ khỏi hàng chờ trước khi đọc: thay đổi commit sau thời điểm này sẽ xếp một lần
            // đẩy mới, nên không bao giờ bỏ sót trạng thái cuối.
            pending.remove(listingId);
            broadcast(listingId);
        });
    }

    @Override
    @Scheduled(fixedDelayString = "${finora.investment.order-book.websocket.heartbeat-interval:PT20S}")
    public void heartbeat() {
        Instant now = clock.instant();
        for (Subscriber subscriber : List.copyOf(sessions.values())) {
            if (!subscriber.session.isOpen()) {
                unsubscribe(subscriber.session);
            } else if (subscriber.lastSeen.plus(properties.idleTimeout()).isBefore(now)) {
                close(subscriber, CloseStatus.SESSION_NOT_RELIABLE);
            } else if (subscriber.openedAt.plus(properties.maxSessionDuration()).isBefore(now)) {
                close(subscriber, SESSION_EXPIRED);
            } else {
                send(subscriber, new PingMessage(PING_PAYLOAD.duplicate()));
            }
        }
    }

    int subscriberCount(Long listingId) {
        Set<Subscriber> listeners = subscribers.get(listingId);
        return listeners == null ? 0 : listeners.size();
    }

    private void broadcast(Long listingId) {
        Set<Subscriber> listeners = subscribers.get(listingId);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        OrderBookSnapshotResponse snapshot;
        try {
            snapshot = orderBookQueryService.snapshot(listingId);
        } catch (RuntimeException failure) {
            log.warn("Không dựng được ảnh sổ lệnh để đẩy: listingId={}, exceptionType={}",
                    listingId, failure.getClass().getName());
            return;
        }
        TextMessage message = toMessage(snapshot);
        if (message == null) {
            return;
        }
        for (Subscriber subscriber : List.copyOf(listeners)) {
            send(subscriber, message);
        }
    }

    private TextMessage toMessage(OrderBookSnapshotResponse snapshot) {
        try {
            return new TextMessage(objectMapper.writeValueAsString(OrderBookStreamMessage.snapshot(snapshot)));
        } catch (JsonProcessingException failure) {
            log.error("Không chuyển được ảnh sổ lệnh sang JSON: listingId={}", snapshot.listingId(), failure);
            return null;
        }
    }

    private void send(Subscriber subscriber, WebSocketMessage<?> message) {
        try {
            subscriber.session.sendMessage(message);
        } catch (IOException | RuntimeException closed) {
            // Client đã đóng kết nối hoặc quá chậm (decorator vượt giới hạn): bỏ phiên, client tự nối lại.
            close(subscriber, CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    private void close(Subscriber subscriber, CloseStatus status) {
        unsubscribe(subscriber.session);
        try {
            if (subscriber.session.isOpen()) {
                subscriber.session.close(status);
            }
        } catch (IOException | RuntimeException ignored) {
            // Phiên đã hỏng; đã gỡ khỏi danh sách nên không cần làm gì thêm.
        }
    }

    @PreDestroy
    void shutdown() {
        broadcaster.shutdownNow();
        List.copyOf(sessions.values()).forEach(subscriber -> close(subscriber, CloseStatus.GOING_AWAY));
    }

    /** Một phiên đang xem sổ. {@code session} đã bọc decorator gửi đồng thời. */
    private static final class Subscriber {
        private final Long listingId;
        private final WebSocketSession session;
        private final Instant openedAt;
        private volatile Instant lastSeen;

        private Subscriber(Long listingId, WebSocketSession session, Instant openedAt) {
            this.listingId = listingId;
            this.session = session;
            this.openedAt = openedAt;
            this.lastSeen = openedAt;
        }
    }
}
