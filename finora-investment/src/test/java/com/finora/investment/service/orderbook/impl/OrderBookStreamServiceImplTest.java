package com.finora.investment.service.orderbook.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.dto.response.OrderBookSnapshotResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.service.orderbook.OrderBookChangedEvent;
import com.finora.investment.service.orderbook.OrderBookQueryService;
import com.finora.investment.service.orderbook.impl.OrderBookStreamTicketServiceImplTest.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Phát ảnh sổ qua WebSocket: ảnh đầu tiên, ảnh sau mỗi thay đổi, nhịp ping và dọn phiên chết. */
class OrderBookStreamServiceImplTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T03:00:00Z"));
    private final OrderBookQueryService queryService = mock(OrderBookQueryService.class);
    private final OrderBookStreamServiceImpl service = new OrderBookStreamServiceImpl(queryService,
            OrderBookStreamTicketServiceImplTest.properties(100), JSON, clock);

    @AfterEach
    void shutdown() {
        service.shutdown();
    }

    @Test
    @DisplayName("Mở phiên nhận ngay ảnh hiện tại, bọc trong tin {type: snapshot, data}")
    void subscribeSendsInitialSnapshot() throws Exception {
        when(queryService.snapshot(7L)).thenReturn(snapshot(7L, 3));
        RecordingWebSocketSession session = new RecordingWebSocketSession("s1");

        service.subscribe(7L, session);

        JsonNode message = json(session.sent.poll(2, TimeUnit.SECONDS));
        assertThat(message.get("type").asText()).isEqualTo("snapshot");
        assertThat(message.get("data").get("listingId").asLong()).isEqualTo(7L);
        assertThat(message.get("data").get("sequence").asLong()).isEqualTo(3L);
        assertThat(service.subscriberCount(7L)).isEqualTo(1);
    }

    @Test
    @DisplayName("Sổ không tồn tại: đóng phiên mã 4404, không giữ phiên")
    void unknownListingClosesWith4404() {
        when(queryService.snapshot(8L)).thenThrow(
                InvestmentDomainException.notFound("LISTING_NOT_FOUND", "Không tìm thấy khoản vay"));
        RecordingWebSocketSession session = new RecordingWebSocketSession("s1");

        service.subscribe(8L, session);

        assertThat(session.closeStatus.getCode()).isEqualTo(4404);
        assertThat(service.subscriberCount(8L)).isZero();
    }

    @Test
    @DisplayName("Sổ đổi thì mọi phiên của sổ đó nhận ảnh mới; phiên của sổ khác không nhận")
    void bookChangeBroadcastsToThatBookOnly() throws Exception {
        when(queryService.snapshot(7L)).thenReturn(snapshot(7L, 1), snapshot(7L, 1), snapshot(7L, 2));
        when(queryService.snapshot(9L)).thenReturn(snapshot(9L, 5));
        RecordingWebSocketSession first = new RecordingWebSocketSession("a");
        RecordingWebSocketSession second = new RecordingWebSocketSession("b");
        RecordingWebSocketSession other = new RecordingWebSocketSession("c");
        service.subscribe(7L, first);
        service.subscribe(7L, second);
        service.subscribe(9L, other);
        first.sent.clear();
        second.sent.clear();
        other.sent.clear();

        service.onBookChanged(new OrderBookChangedEvent(7L, 2));

        assertThat(json(first.sent.poll(2, TimeUnit.SECONDS)).get("data").get("sequence").asLong()).isEqualTo(2L);
        assertThat(json(second.sent.poll(2, TimeUnit.SECONDS)).get("data").get("sequence").asLong()).isEqualTo(2L);
        assertThat(other.sent.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("Gửi lỗi (client đã rớt) thì gỡ phiên đó, phiên khác vẫn nhận")
    void failedSendDropsOnlyThatSession() throws Exception {
        when(queryService.snapshot(7L)).thenReturn(snapshot(7L, 1));
        RecordingWebSocketSession dead = new RecordingWebSocketSession("dead");
        RecordingWebSocketSession alive = new RecordingWebSocketSession("alive");
        service.subscribe(7L, dead);
        service.subscribe(7L, alive);
        alive.sent.clear();
        dead.failSends = true;

        service.onBookChanged(new OrderBookChangedEvent(7L, 2));

        assertThat(alive.sent.poll(2, TimeUnit.SECONDS)).isNotNull();
        // Luồng đẩy có thể gửi cho phiên sống trước phiên chết: chờ nó xử lý xong cả hai.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (service.subscriberCount(7L) != 1 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(service.subscriberCount(7L)).isEqualTo(1);
        assertThat(dead.closeStatus).isEqualTo(CloseStatus.SESSION_NOT_RELIABLE);
    }

    @Test
    @DisplayName("Nhịp giữ kết nối gửi ping; phiên có pong được giữ, phiên im lặng quá idle-timeout bị đóng")
    void heartbeatPingsAndClosesSilentSessions() throws Exception {
        when(queryService.snapshot(7L)).thenReturn(snapshot(7L, 1));
        RecordingWebSocketSession answering = new RecordingWebSocketSession("pong");
        RecordingWebSocketSession silent = new RecordingWebSocketSession("silent");
        service.subscribe(7L, answering);
        service.subscribe(7L, silent);
        answering.sent.clear();
        silent.sent.clear();

        clock.advance(Duration.ofSeconds(20));
        service.heartbeat();
        assertThat(answering.sent.poll()).isInstanceOf(PingMessage.class);
        assertThat(silent.sent.poll()).isInstanceOf(PingMessage.class);
        service.touch(answering);

        clock.advance(Duration.ofSeconds(50));
        service.heartbeat();

        assertThat(silent.closeStatus).isEqualTo(CloseStatus.SESSION_NOT_RELIABLE);
        assertThat(answering.isOpen()).isTrue();
        assertThat(answering.sent.poll()).isInstanceOf(PingMessage.class);
        assertThat(service.subscriberCount(7L)).isEqualTo(1);
    }

    @Test
    @DisplayName("Phiên mở quá max-session-duration bị đóng mã 4000 để client lấy vé mới")
    void longSessionIsClosedForNewTicket() {
        when(queryService.snapshot(7L)).thenReturn(snapshot(7L, 1));
        RecordingWebSocketSession session = new RecordingWebSocketSession("long");
        service.subscribe(7L, session);

        for (int i = 0; i < 91; i++) {
            clock.advance(Duration.ofSeconds(20));
            service.touch(session);
            service.heartbeat();
        }

        assertThat(session.closeStatus.getCode()).isEqualTo(4000);
        assertThat(service.subscriberCount(7L)).isZero();
    }

    @Test
    @DisplayName("Đóng phiên thì gỡ khỏi danh sách, sổ không còn ai xem thì không dựng ảnh nữa")
    void unsubscribeRemovesSession() {
        when(queryService.snapshot(7L)).thenReturn(snapshot(7L, 1));
        RecordingWebSocketSession session = new RecordingWebSocketSession("s");
        service.subscribe(7L, session);

        service.unsubscribe(session);

        assertThat(service.subscriberCount(7L)).isZero();
    }

    static OrderBookSnapshotResponse snapshot(Long listingId, long sequence) {
        return new OrderBookSnapshotResponse(listingId, 1L, sequence, "B", "15.0000", 12, "100000.00", null,
                false, null, null, null, null, null, List.of(), List.of(), List.of(), "Vốn kinh doanh", "BUSINESS");
    }

    private static JsonNode json(WebSocketMessage<?> message) throws Exception {
        assertThat(message).isInstanceOf(TextMessage.class);
        return JSON.readTree(((TextMessage) message).getPayload());
    }
}
