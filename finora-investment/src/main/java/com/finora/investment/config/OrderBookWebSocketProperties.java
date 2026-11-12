package com.finora.investment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Kênh WebSocket đẩy sổ lệnh theo thời gian thực ({@code /ws/order-books/{listingId}}).
 *
 * @param allowedOriginPatterns origin được mở kết nối (web Vercel, dev server, domain API cho React Native). Request
 *                              không có Origin hoặc Origin trùng host thì luôn qua; origin lạ bị từ chối lúc bắt tay (403)
 * @param ticketTtl             thời hạn vé bắt tay; vé chỉ dùng một lần
 * @param maxTickets            số vé chưa dùng giữ tối đa trong bộ nhớ, chặn bị gọi API vé dồn dập làm đầy heap
 * @param heartbeatInterval     nhịp gửi khung ping; proxy (Caddy, Azure) không cắt kết nối khi sổ lâu không đổi
 * @param idleTimeout           quá lâu không nhận pong hay tin nào từ client thì coi là phiên chết và đóng
 * @param maxSessionDuration    phiên mở lâu hơn thì đóng để client lấy vé mới, tức là kiểm lại quyền định kỳ
 * @param sendTimeLimit         một lần gửi chờ client chậm tối đa bao lâu trước khi đóng phiên đó
 * @param sendBufferSizeLimit   số byte chờ gửi tối đa của một phiên trước khi đóng phiên đó
 */
@ConfigurationProperties(prefix = "finora.investment.order-book.websocket")
public record OrderBookWebSocketProperties(
        List<String> allowedOriginPatterns,
        Duration ticketTtl,
        int maxTickets,
        Duration heartbeatInterval,
        Duration idleTimeout,
        Duration maxSessionDuration,
        Duration sendTimeLimit,
        int sendBufferSizeLimit
) {
    public OrderBookWebSocketProperties {
        allowedOriginPatterns = allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()
                ? List.of("http://localhost:*", "http://127.0.0.1:*")
                : allowedOriginPatterns.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
        ticketTtl = ticketTtl == null ? Duration.ofSeconds(30) : ticketTtl;
        maxTickets = maxTickets == 0 ? 10_000 : maxTickets;
        heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(20) : heartbeatInterval;
        idleTimeout = idleTimeout == null ? Duration.ofSeconds(65) : idleTimeout;
        maxSessionDuration = maxSessionDuration == null ? Duration.ofMinutes(30) : maxSessionDuration;
        sendTimeLimit = sendTimeLimit == null ? Duration.ofSeconds(10) : sendTimeLimit;
        sendBufferSizeLimit = sendBufferSizeLimit == 0 ? 512 * 1024 : sendBufferSizeLimit;
        if (ticketTtl.isNegative() || ticketTtl.isZero() || maxTickets < 1
                || idleTimeout.compareTo(heartbeatInterval) <= 0) {
            throw new IllegalArgumentException("Cấu hình WebSocket sổ lệnh không hợp lệ");
        }
    }
}
