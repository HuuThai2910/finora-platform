package com.finora.payment.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PaymentE2EPausePointsTest {

    @Test
    void pausePointIsRefusedWheneverRealMoneyProvidersAreConfigured() {
        String point = PaymentE2EPausePoints.PROVIDER_DISBURSED_BEFORE_SAVE;
        assertThatThrownBy(() -> new PaymentE2EPausePoints(point, Duration.ofSeconds(30), "zalopay", "mock"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PaymentE2EPausePoints(point, Duration.ofSeconds(30), "mock", "zalopay"))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new PaymentE2EPausePoints(point, Duration.ofSeconds(30), "mock", "mock"))
                .doesNotThrowAnyException();
        assertThatCode(() -> new PaymentE2EPausePoints("", Duration.ZERO, "zalopay", "zalopay"))
                .doesNotThrowAnyException();
    }

    @Test
    void otherPointsAndADisabledHookNeverWait() {
        PaymentE2EPausePoints points = new PaymentE2EPausePoints("another-point", Duration.ofHours(1), "mock", "mock");
        assertThatCode(() -> points.pause(PaymentE2EPausePoints.PROVIDER_DISBURSED_BEFORE_SAVE)).doesNotThrowAnyException();
        assertThatCode(() -> PaymentE2EPausePoints.disabled().pause(PaymentE2EPausePoints.PROVIDER_DISBURSED_BEFORE_SAVE))
                .doesNotThrowAnyException();
    }
}
