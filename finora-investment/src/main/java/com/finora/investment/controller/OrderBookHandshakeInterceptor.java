package com.finora.investment.controller;

import com.finora.investment.service.orderbook.OrderBookStreamTicketService;
import com.finora.investment.service.orderbook.OrderBookStreamTicketService.StreamTicket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;

/**
 * Kiểm vé lúc bắt tay {@code GET /ws/order-books/{listingId}?ticket=...}.
 *
 * <p>Origin đã được kiểm trước bước này (danh sách {@code allowed-origin-patterns}; request không có Origin thì
 * qua). Vé thiếu, sai, đã dùng, hết hạn hoặc cấp cho sổ khác thì trả 401 và không nâng cấp kết nối. Đường dẫn sai
 * dạng trả 400.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBookHandshakeInterceptor implements HandshakeInterceptor {

    public static final String LISTING_ID_ATTRIBUTE = "orderBook.listingId";
    public static final String USER_ID_ATTRIBUTE = "orderBook.userId";

    private static final String PATH_PREFIX = "/ws/order-books/";

    private final OrderBookStreamTicketService orderBookStreamTicketService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Optional<Long> listingId = listingId(request.getURI().getPath());
        if (listingId.isEmpty()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
        Optional<StreamTicket> redeemed = orderBookStreamTicketService.redeem(ticket, listingId.get());
        if (redeemed.isEmpty()) {
            log.debug("Từ chối bắt tay WebSocket sổ lệnh: listingId={}, có vé={}", listingId.get(), ticket != null);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(LISTING_ID_ATTRIBUTE, redeemed.get().listingId());
        attributes.put(USER_ID_ATTRIBUTE, redeemed.get().userId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // Không có việc gì sau bắt tay: ảnh đầu tiên gửi ở OrderBookWebSocketHandler khi kết nối đã mở.
    }

    static Optional<Long> listingId(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX)) {
            return Optional.empty();
        }
        String segment = path.substring(PATH_PREFIX.length());
        if (segment.endsWith("/")) {
            segment = segment.substring(0, segment.length() - 1);
        }
        if (segment.isEmpty() || segment.length() > 18 || !segment.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        long id = Long.parseLong(segment);
        return id > 0 ? Optional.of(id) : Optional.empty();
    }
}
