package com.finora.investment.service.outbox.impl;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentOutboxRelayWorkerRegistrationTest {

    /**
     * Publisher là @Bean của InvestmentKafkaOutboxConfiguration; trong fat jar @Component được quét trước nên
     * @ConditionalOnBean luôn sai, worker bị bỏ và outbox nằm PENDING (lỗi gặp lại ngày 01/11/2026 khi chạy cụm Docker).
     */
    @Test
    void relayWorkerDoesNotDependOnBeanRegistrationOrder() {
        assertThat(InvestmentOutboxRelayWorkerImpl.class.isAnnotationPresent(ConditionalOnBean.class)).isFalse();
    }
}
