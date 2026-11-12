package com.finora.investment.service.orderbook.impl;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.ops.OpsFunction;
import com.finora.investment.config.OrderBookWebSocketProperties;
import com.finora.investment.dto.response.OrderBookStreamTicketResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.service.orderbook.OrderBookStreamTicketService.StreamTicket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Kho vé bắt tay WebSocket: vé dùng một lần, hết hạn, gắn đúng sổ; quyền xin vé như đọc sổ qua REST. */
class OrderBookStreamTicketServiceImplTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T03:00:00Z"));
    private final MarketListingRepository listingRepository = mock(MarketListingRepository.class);
    private OrderBookStreamTicketServiceImpl service;

    @BeforeEach
    void setUp() {
        when(listingRepository.existsById(anyLong())).thenReturn(true);
        service = new OrderBookStreamTicketServiceImpl(listingRepository, properties(3), clock);
        actAs("INV-1", "ROLE_INVESTOR");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Vé hợp lệ đổi được đúng một lần, trả người giữ vé và sổ")
    void validTicketRedeemsOnce() {
        OrderBookStreamTicketResponse issued = service.issue(12L);

        assertThat(issued.path()).isEqualTo("/ws/order-books/12");
        assertThat(issued.expiresAt()).isEqualTo(clock.instant().plusSeconds(30));
        assertThat(issued.ticket()).hasSizeGreaterThanOrEqualTo(40).doesNotContain("INV-1");

        assertThat(service.redeem(issued.ticket(), 12L)).contains(new StreamTicket(12L, "INV-1"));
        // Dùng lại vé đã đổi: từ chối.
        assertThat(service.redeem(issued.ticket(), 12L)).isEmpty();
    }

    @Test
    @DisplayName("Vé quá hạn bị từ chối")
    void expiredTicketIsRejected() {
        OrderBookStreamTicketResponse issued = service.issue(12L);
        clock.advance(Duration.ofSeconds(30));

        assertThat(service.redeem(issued.ticket(), 12L)).isEmpty();
    }

    @Test
    @DisplayName("Vé của sổ khác bị từ chối và bị huỷ luôn, không thử lại được với sổ đúng")
    void ticketForAnotherBookIsRejectedAndBurned() {
        OrderBookStreamTicketResponse issued = service.issue(12L);

        assertThat(service.redeem(issued.ticket(), 13L)).isEmpty();
        assertThat(service.redeem(issued.ticket(), 12L)).isEmpty();
    }

    @Test
    @DisplayName("Vé không tồn tại hoặc rỗng bị từ chối")
    void unknownOrBlankTicketIsRejected() {
        assertThat(service.redeem("khong-co-ve-nay", 12L)).isEmpty();
        assertThat(service.redeem("", 12L)).isEmpty();
        assertThat(service.redeem(null, 12L)).isEmpty();
    }

    @Test
    @DisplayName("Sổ không tồn tại thì không cấp vé")
    void unknownListingGetsNoTicket() {
        when(listingRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.issue(99L))
                .isInstanceOfSatisfying(InvestmentDomainException.class,
                        e -> assertThat(e.getKind()).isEqualTo(InvestmentDomainException.Kind.NOT_FOUND));
    }

    @Test
    @DisplayName("Nhân viên thiếu quyền Xem Chợ chuyển nhượng không lấy được vé; có quyền hoặc là quản trị viên thì được")
    void staffNeedsSecondaryMarketView() {
        actAs("STAFF-1", "ROLE_STAFF");
        assertThatThrownBy(() -> service.issue(12L))
                .isInstanceOfSatisfying(InvestmentDomainException.class,
                        e -> assertThat(e.getKind()).isEqualTo(InvestmentDomainException.Kind.FORBIDDEN));

        actAs("STAFF-2", "ROLE_STAFF", OpsFunction.SECONDARY_MARKET.view());
        assertThat(service.issue(12L).ticket()).isNotBlank();

        actAs("ADMIN-1", "ROLE_ADMIN");
        assertThat(service.issue(12L).ticket()).isNotBlank();
    }

    @Test
    @DisplayName("Kho đầy thì dọn vé hết hạn trước; vẫn đầy thì trả 429")
    void fullStorePurgesExpiredThenRejects() {
        service.issue(1L);
        service.issue(2L);
        service.issue(3L);
        assertThatThrownBy(() -> service.issue(4L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ORDER_BOOK_STREAM_BUSY"));

        clock.advance(Duration.ofSeconds(31));
        assertThat(service.issue(4L).ticket()).isNotBlank();
        assertThat(service.pendingTicketCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dọn định kỳ chỉ bỏ vé đã hết hạn")
    void purgeKeepsLiveTickets() {
        service.issue(1L);
        clock.advance(Duration.ofSeconds(20));
        OrderBookStreamTicketResponse live = service.issue(2L);
        clock.advance(Duration.ofSeconds(15));

        service.purgeExpired();

        assertThat(service.pendingTicketCount()).isEqualTo(1);
        assertThat(service.redeem(live.ticket(), 2L)).isPresent();
    }

    static OrderBookWebSocketProperties properties(int maxTickets) {
        return new OrderBookWebSocketProperties(List.of("http://localhost:*"), Duration.ofSeconds(30), maxTickets,
                Duration.ofSeconds(20), Duration.ofSeconds(65), Duration.ofMinutes(30), Duration.ofSeconds(10),
                512 * 1024);
    }

    private static void actAs(String userId, String... authorities) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("sub", userId)
                .claim("user_id", userId)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList(), userId));
    }

    /** Đồng hồ chỉnh tay được để thử vé hết hạn. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
