package com.finora.loan.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class E2EPausePointsTest {

    @Test
    void pausePointIsRefusedOutsideTheMockSignatureLocalStack() {
        assertThatThrownBy(() -> new E2EPausePoints(E2EPausePoints.CORE_BOOKED_BEFORE_SAVE, Duration.ofSeconds(30),
                "SMARTCA", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new E2EPausePoints(E2EPausePoints.CORE_BOOKED_BEFORE_SAVE, Duration.ofSeconds(30),
                "MOCK", false)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new E2EPausePoints(E2EPausePoints.CORE_BOOKED_BEFORE_SAVE, Duration.ofSeconds(30),
                "MOCK", true)).doesNotThrowAnyException();
        // Không cấu hình điểm dừng thì môi trường nào cũng chạy bình thường.
        assertThatCode(() -> new E2EPausePoints("", Duration.ZERO, "SMARTCA", false)).doesNotThrowAnyException();
    }

    @Test
    void otherPointsAndADisabledHookNeverWait() {
        E2EPausePoints points = new E2EPausePoints("another-point", Duration.ofHours(1), "MOCK", true);
        assertThatCode(() -> points.pause(E2EPausePoints.CORE_BOOKED_BEFORE_SAVE)).doesNotThrowAnyException();
        assertThatCode(() -> E2EPausePoints.disabled().pause(E2EPausePoints.CORE_BOOKED_BEFORE_SAVE))
                .doesNotThrowAnyException();
    }
}
