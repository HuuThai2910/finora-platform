package com.finora.investment.config;

import com.finora.investment.controller.OrderBookHandshakeInterceptor;
import com.finora.investment.controller.OrderBookWebSocketHandler;
import com.finora.investment.service.orderbook.OrderBookStreamTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket thuần (không STOMP, không SockJS) cho sổ lệnh thời gian thực.
 *
 * <p>Đường đi: client → Caddy → thẳng {@code investment:8084}. Gateway (Spring Cloud Gateway MVC) không chuyển tiếp
 * WebSocket nên route {@code /ws/*} trong Caddyfile đi vòng qua Gateway; vì vậy kênh không có cookie phiên hay header
 * {@code Authorization} mà dùng vé dùng một lần (xem {@link OrderBookStreamTicketService}).</p>
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(OrderBookWebSocketProperties.class)
@RequiredArgsConstructor
public class OrderBookWebSocketConfig implements WebSocketConfigurer {

    private final OrderBookWebSocketHandler orderBookWebSocketHandler;
    private final OrderBookHandshakeInterceptor orderBookHandshakeInterceptor;
    private final OrderBookWebSocketProperties orderBookWebSocketProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Kiểm Origin chạy trước kiểm vé (Spring đặt bộ kiểm Origin đầu chuỗi), nên origin lạ không làm tốn vé.
        // Request không có Origin hoặc Origin trùng host được coi là cùng origin và đi tiếp. WebSocket của React Native
        // gửi Origin bằng chính domain đích; sau Caddy (https → http) phải liệt kê domain API trong danh sách.
        registry.addHandler(orderBookWebSocketHandler, OrderBookStreamTicketService.PATH_PATTERN)
                .addInterceptors(orderBookHandshakeInterceptor)
                .setAllowedOriginPatterns(orderBookWebSocketProperties.allowedOriginPatterns().toArray(String[]::new));
    }
}
