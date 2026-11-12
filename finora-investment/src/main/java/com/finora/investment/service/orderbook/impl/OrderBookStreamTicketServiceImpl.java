package com.finora.investment.service.orderbook.impl;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ops.OpsFunction;
import com.finora.investment.config.OrderBookWebSocketProperties;
import com.finora.investment.dto.response.OrderBookStreamTicketResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.service.orderbook.OrderBookStreamTicketService;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kho vé bắt tay WebSocket sổ lệnh, giữ trong bộ nhớ.
 *
 * <p>Giới hạn: vé chỉ nằm trong bộ nhớ của instance đã cấp. Investment hiện chạy một instance nên đủ; chạy nhiều
 * instance thì vé cấp ở instance này không dùng được ở instance kia (trừ khi proxy gắn phiên), khi đó cần chuyển kho
 * vé sang nơi dùng chung (bảng PostgreSQL của Investment, như kho tạm của User). Khởi động lại service thì vé
 * đang chờ mất, client chỉ việc xin vé mới.</p>
 */
@Service
public class OrderBookStreamTicketServiceImpl implements OrderBookStreamTicketService {

    private static final int TICKET_BYTES = 32;

    private final MarketListingRepository marketListingRepository;
    private final OrderBookWebSocketProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, IssuedTicket> tickets = new ConcurrentHashMap<>();

    public OrderBookStreamTicketServiceImpl(
            MarketListingRepository marketListingRepository,
            OrderBookWebSocketProperties properties,
            Clock clock) {
        this.marketListingRepository = marketListingRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public OrderBookStreamTicketResponse issue(Long listingId) {
        String userId = SecurityUtils.getCurrentUserId();
        // Nhân viên chỉ xem sổ khi được giao quyền Xem Chợ chuyển nhượng; quản trị viên luôn qua (hasPermission).
        if (SecurityUtils.hasRole(SecurityUtils.ROLE_STAFF)
                && !SecurityUtils.hasPermission(OpsFunction.SECONDARY_MARKET.view())) {
            throw InvestmentDomainException.forbidden("PERMISSION_REQUIRED",
                    "Tài khoản không có quyền xem Chợ chuyển nhượng");
        }
        if (!marketListingRepository.existsById(listingId)) {
            throw InvestmentDomainException.notFound("LISTING_NOT_FOUND", "Không tìm thấy khoản vay");
        }
        if (tickets.size() >= properties.maxTickets()) {
            purgeExpired();
            if (tickets.size() >= properties.maxTickets()) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "ORDER_BOOK_STREAM_BUSY",
                        "Hệ thống đang bận, vui lòng thử lại sau ít phút");
            }
        }

        String ticket = newTicket();
        Instant expiresAt = clock.instant().plus(properties.ticketTtl());
        tickets.put(ticket, new IssuedTicket(listingId, userId, expiresAt));
        return new OrderBookStreamTicketResponse(ticket, "/ws/order-books/" + listingId, expiresAt);
    }

    @Override
    public Optional<StreamTicket> redeem(String ticket, Long listingId) {
        if (ticket == null || ticket.isBlank() || listingId == null) {
            return Optional.empty();
        }
        // remove() là bước nguyên tử: hai lượt bắt tay cùng vé thì chỉ một lượt lấy được.
        IssuedTicket issued = tickets.remove(ticket);
        if (issued == null || !issued.expiresAt().isAfter(clock.instant()) || !issued.listingId().equals(listingId)) {
            return Optional.empty();
        }
        return Optional.of(new StreamTicket(listingId, issued.userId()));
    }

    @Override
    @Scheduled(fixedDelayString = "${finora.investment.order-book.websocket.ticket-cleanup-ms:60000}")
    public void purgeExpired() {
        Instant now = clock.instant();
        tickets.values().removeIf(issued -> !issued.expiresAt().isAfter(now));
    }

    int pendingTicketCount() {
        return tickets.size();
    }

    private String newTicket() {
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record IssuedTicket(Long listingId, String userId, Instant expiresAt) {
    }
}
