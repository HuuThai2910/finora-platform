package com.finora.loan.service.operations.impl;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

class FineractEndOfDayHealthIndicatorImplTest {

    /**
     * Actuator đặt tên health theo tên bean (bỏ hậu tố {@code HealthIndicator}); nhóm {@code operations} trong
     * application.yml tham chiếu {@code fineractEndOfDay}, nên tên bean không được đổi theo tên class Impl.
     */
    @Test
    void beanNameKeepsTheFineractEndOfDayHealthContributorName() {
        assertThat(FineractEndOfDayHealthIndicatorImpl.class.getAnnotation(Component.class).value())
                .isEqualTo("fineractEndOfDayHealthIndicator");
    }
}
