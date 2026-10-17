package com.finora.payment.config;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Điểm dừng chỉ cho drill E2E (Q13): giữ luồng đúng chỗ nguy hiểm để harness tắt JVM và chứng minh khởi động lại không
 * chuyển tiền hai lần. Cấu hình rỗng là không dừng. Chỉ nhận khi provider giải ngân và nạp tiền đều là {@code mock}, như
 * đồng hồ E2E; không bao giờ dừng khi có tiền thật.
 */
@Slf4j
@Component
public class PaymentE2EPausePoints {
    /** Provider đã xác nhận chuyển tiền giải ngân nhưng Payment chưa lưu kết quả. */
    public static final String PROVIDER_DISBURSED_BEFORE_SAVE = "provider-disbursed-before-save";

    private final String point;
    private final Duration duration;

    @Autowired
    public PaymentE2EPausePoints(@Value("${finora.e2e.pause-point:}") String point,
            @Value("${finora.e2e.pause-duration:PT0S}") Duration duration,
            @Value("${finora.payment.disbursement.provider:mock}") String disbursementProvider,
            @Value("${finora.payment.top-up.provider:mock}") String topUpProvider) {
        this.point = point == null ? "" : point.trim();
        this.duration = duration == null ? Duration.ZERO : duration;
        if (enabled() && (!"mock".equalsIgnoreCase(disbursementProvider) || !"mock".equalsIgnoreCase(topUpProvider))) {
            throw new IllegalStateException("finora.e2e.pause-point chỉ dùng khi provider giải ngân và nạp tiền đều là mock");
        }
    }

    public static PaymentE2EPausePoints disabled() {
        return new PaymentE2EPausePoints("", Duration.ZERO, "mock", "mock");
    }

    public void pause(String name) {
        if (!enabled() || !point.equals(name)) return;
        log.warn("E2E_PAUSE point={} seconds={} (drill Q13)", name, duration.toSeconds());
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean enabled() {
        return !point.isEmpty() && duration.isPositive();
    }
}
