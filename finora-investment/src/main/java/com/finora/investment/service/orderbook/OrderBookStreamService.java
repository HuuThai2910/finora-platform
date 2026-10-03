package com.finora.investment.service.orderbook;

import com.finora.investment.dto.response.OrderBookSnapshotResponse;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Đẩy sổ lệnh theo thời gian thực bằng Server-Sent Events.
 *
 * <p>Chọn SSE thay vì WebSocket: dữ liệu chỉ chảy một chiều (server → client), còn đặt/huỷ lệnh
 * vẫn qua REST có idempotency. SSE là HTTP thường nên đi qua Gateway MVC, cookie đăng nhập của web
 * và cấu hình CORS sẵn có mà không cần thêm giao thức.</p>
 *
 * <p>Mỗi lần đẩy là <b>cả ảnh chụp</b> (độ sâu 20 mức mỗi phía, 20 giao dịch gần nhất), không phải
 * phần chênh lệch. Sổ của một khoản vay P2P nhỏ, nên gửi cả ảnh rẻ hơn nhiều so với cái giá phải
 * giữ đúng thứ tự và bù gói bị mất của luồng chênh lệch. Client giữ ảnh có {@code sequence} lớn
 * nhất.</p>
 *
 * <p>Giới hạn: danh sách người nghe nằm trong bộ nhớ của từng instance. Chạy nhiều instance thì
 * client chỉ nhận cập nhật từ lệnh đi qua đúng instance của mình — cần kênh phát chung (Kafka hoặc
 * PostgreSQL LISTEN/NOTIFY) khi mở rộng. Bản hiện tại chạy một instance.</p>
 */
@Slf4j
@Service
public class OrderBookStreamService {

    private final OrderBookQueryService queryService;
    private final long emitterTimeoutMs;

    private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** Sổ đang chờ đẩy ảnh mới; gom nhiều thay đổi liên tiếp thành một lần đẩy. */
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();

    /**
     * Một luồng duy nhất dựng và gửi ảnh chụp: ảnh của cùng một sổ đi ra theo đúng thứ tự commit,
     * và việc chờ client chậm không chiếm luồng xử lý request.
     */
    private final ExecutorService broadcaster = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "order-book-broadcaster");
        thread.setDaemon(true);
        return thread;
    });

    public OrderBookStreamService(
            OrderBookQueryService queryService,
            @Value("${finora.investment.order-book.stream-timeout-ms:1800000}") long emitterTimeoutMs) {
        this.queryService = queryService;
        this.emitterTimeoutMs = emitterTimeoutMs;
    }

    /** Mở stream cho một sổ; gửi ngay ảnh hiện tại để client không phải gọi REST riêng. */
    public SseEmitter subscribe(Long listingId) {
        OrderBookSnapshotResponse initial = queryService.snapshot(listingId);

        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        Set<SseEmitter> listeners = subscribers.computeIfAbsent(listingId, id -> new CopyOnWriteArraySet<>());
        listeners.add(emitter);
        Runnable remove = () -> listeners.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());

        if (!send(emitter, initial)) {
            listeners.remove(emitter);
        }
        return emitter;
    }

    /** Chỉ chạy sau khi transaction đổi sổ đã commit, để client không thấy trạng thái bị rollback. */
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

    /** Gói giữ kết nối, để proxy và trình duyệt không cắt stream khi sổ lâu không đổi. */
    @Scheduled(fixedDelayString = "${finora.investment.order-book.heartbeat-ms:20000}")
    public void heartbeat() {
        subscribers.forEach((listingId, listeners) -> listeners.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException closed) {
                listeners.remove(emitter);
            }
        }));
    }

    int subscriberCount(Long listingId) {
        Set<SseEmitter> listeners = subscribers.get(listingId);
        return listeners == null ? 0 : listeners.size();
    }

    private void broadcast(Long listingId) {
        Set<SseEmitter> listeners = subscribers.get(listingId);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        OrderBookSnapshotResponse snapshot;
        try {
            snapshot = queryService.snapshot(listingId);
        } catch (RuntimeException failure) {
            log.warn("Không dựng được ảnh sổ lệnh để đẩy: listingId={}, exceptionType={}",
                    listingId, failure.getClass().getName());
            return;
        }
        listeners.removeIf(emitter -> !send(emitter, snapshot));
    }

    private boolean send(SseEmitter emitter, OrderBookSnapshotResponse snapshot) {
        try {
            emitter.send(SseEmitter.event()
                    .name("snapshot")
                    .id(Long.toString(snapshot.sequence()))
                    .data(snapshot, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException closed) {
            // Client đã đóng kết nối: bỏ khỏi danh sách, không phải lỗi hệ thống.
            emitter.completeWithError(closed);
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        broadcaster.shutdownNow();
        subscribers.values().forEach(listeners -> listeners.forEach(SseEmitter::complete));
    }
}
