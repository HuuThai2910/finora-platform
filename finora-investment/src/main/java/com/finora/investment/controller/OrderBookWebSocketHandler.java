package com.finora.investment.controller;

import com.finora.investment.service.orderbook.OrderBookStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * Biên WebSocket của sổ lệnh ({@code /ws/order-books/{listingId}}): phiên đã qua kiểm vé
 * ({@link OrderBookHandshakeInterceptor}) được giao cho {@link OrderBookStreamService}.
 *
 * <p>Kênh một chiều server → client. Tin nhắn hay khung pong client gửi lên chỉ cho biết phiên còn sống; nội dung bị
 * bỏ qua, không có thao tác nghiệp vụ nào đi qua đây.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBookWebSocketHandler extends AbstractWebSocketHandler {

    private final OrderBookStreamService orderBookStreamService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Object listingId = session.getAttributes().get(OrderBookHandshakeInterceptor.LISTING_ID_ATTRIBUTE);
        if (!(listingId instanceof Long id)) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        orderBookStreamService.subscribe(id, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        orderBookStreamService.touch(session);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        orderBookStreamService.touch(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Lỗi đường truyền WebSocket sổ lệnh: sessionId={}, exceptionType={}",
                session.getId(), exception.getClass().getName());
        orderBookStreamService.unsubscribe(session);
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        orderBookStreamService.unsubscribe(session);
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (Exception ignored) {
            // Phiên đã hỏng.
        }
    }
}
