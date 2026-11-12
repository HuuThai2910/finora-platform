package com.finora.investment.controller;

import com.finora.investment.service.orderbook.OrderBookStreamTicketService;
import com.finora.investment.service.orderbook.OrderBookStreamTicketService.StreamTicket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Bắt tay WebSocket sổ lệnh: vé hợp lệ thì nâng cấp, còn lại 401; đường dẫn sai dạng 400. */
class OrderBookHandshakeInterceptorTest {

    private final OrderBookStreamTicketService ticketService = mock(OrderBookStreamTicketService.class);
    private final OrderBookHandshakeInterceptor interceptor = new OrderBookHandshakeInterceptor(ticketService);

    @Test
    @DisplayName("Vé hợp lệ: cho bắt tay và gắn mã sổ, mã người dùng vào phiên")
    void validTicketAccepted() {
        when(ticketService.redeem("good", 12L)).thenReturn(Optional.of(new StreamTicket(12L, "INV-1")));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(request("/ws/order-books/12", "ticket=good"),
                new ServletServerHttpResponse(servletResponse), null, attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(OrderBookHandshakeInterceptor.LISTING_ID_ATTRIBUTE, 12L)
                .containsEntry(OrderBookHandshakeInterceptor.USER_ID_ATTRIBUTE, "INV-1");
    }

    @Test
    @DisplayName("Vé sai, hết hạn, đã dùng hoặc của sổ khác (kho vé trả rỗng): 401")
    void rejectedTicketGets401() {
        when(ticketService.redeem(any(), anyLong())).thenReturn(Optional.empty());

        assertRejected("/ws/order-books/12", "ticket=used", 401);
        assertRejected("/ws/order-books/12", null, 401);
    }

    @Test
    @DisplayName("Đường dẫn không có mã sổ hợp lệ: 400 và không đụng tới vé")
    void malformedPathGets400() {
        assertRejected("/ws/order-books/abc", "ticket=x", 400);
        assertRejected("/ws/order-books/", "ticket=x", 400);
        assertRejected("/ws/order-books/0", "ticket=x", 400);
        assertRejected("/ws/order-books/12/extra", "ticket=x", 400);
        verify(ticketService, never()).redeem(any(), anyLong());
    }

    private void assertRejected(String path, String query, int status) {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        ServletServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

        boolean accepted = interceptor.beforeHandshake(request(path, query), response, null, new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(servletResponse.getStatus()).isEqualTo(status);
    }

    private static ServletServerHttpRequest request(String path, String query) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", path);
        servletRequest.setServerName("localhost");
        servletRequest.setServerPort(8084);
        servletRequest.setQueryString(query);
        return new ServletServerHttpRequest(servletRequest);
    }
}
