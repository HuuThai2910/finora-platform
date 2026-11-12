package com.finora.investment.service.orderbook.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.service.orderbook.OrderBookChangedEvent;
import com.finora.investment.service.orderbook.OrderBookQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ảnh mới chỉ đi ra sau khi transaction đổi sổ commit: trong transaction chưa gửi gì, rollback thì không gửi. Dùng
 * {@link NoOpTransactionManager} để chạy đúng cơ chế {@code @TransactionalEventListener(AFTER_COMMIT)} mà không cần DB.
 */
@SpringJUnitConfig(OrderBookStreamAfterCommitTest.Config.class)
class OrderBookStreamAfterCommitTest {

    @Configuration
    @EnableTransactionManagement
    static class Config {

        @Bean
        OrderBookQueryService orderBookQueryService() {
            OrderBookQueryService queryService = mock(OrderBookQueryService.class);
            when(queryService.snapshot(5L)).thenReturn(
                    OrderBookStreamServiceImplTest.snapshot(5L, 1),
                    OrderBookStreamServiceImplTest.snapshot(5L, 2));
            return queryService;
        }

        @Bean
        OrderBookStreamServiceImpl orderBookStreamService(OrderBookQueryService orderBookQueryService) {
            return new OrderBookStreamServiceImpl(orderBookQueryService,
                    OrderBookStreamTicketServiceImplTest.properties(100), new ObjectMapper().findAndRegisterModules(),
                    new OrderBookStreamTicketServiceImplTest.MutableClock(Instant.parse("2026-11-10T03:00:00Z")));
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }
    }

    @Autowired
    private OrderBookStreamServiceImpl streamService;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Trong transaction chưa đẩy; commit xong mới đẩy ảnh mới; rollback thì không đẩy")
    void pushesOnlyAfterCommit() throws Exception {
        RecordingWebSocketSession session = new RecordingWebSocketSession("after-commit");
        streamService.subscribe(5L, session);
        assertThat(session.sent.poll(2, TimeUnit.SECONDS)).isNotNull();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new OrderBookChangedEvent(5L, 2));
            try {
                assertThat(session.sent.poll(300, TimeUnit.MILLISECONDS)).as("chưa commit").isNull();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(session.sent.poll(2, TimeUnit.SECONDS)).as("sau commit").isNotNull();

        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new OrderBookChangedEvent(5L, 3));
            status.setRollbackOnly();
        });
        assertThat(session.sent.poll(500, TimeUnit.MILLISECONDS)).as("rollback").isNull();
    }
}
