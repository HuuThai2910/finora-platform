package com.finora.loan.config;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Điểm dừng chỉ cho drill E2E (Q13): giữ luồng đúng chỗ nguy hiểm để harness tắt JVM và chứng minh khởi động lại không ghi
 * core hai lần. Cấu hình rỗng là không dừng. Chỉ nhận khi chữ ký {@code MOCK} và profile {@code local}, như đồng hồ E2E;
 * môi trường thật không bao giờ có điểm dừng.
 */
@Slf4j
@Component
public class E2EPausePoints {
    /** Fineract đã ghi và giải ngân khoản vay nhưng saga chưa lưu kết quả. */
    public static final String CORE_BOOKED_BEFORE_SAVE = "core-booked-before-save";

    private final String point;
    private final Duration duration;

    @Autowired
    public E2EPausePoints(@Value("${finora.e2e.pause-point:}") String point,
            @Value("${finora.e2e.pause-duration:PT0S}") String duration,
            @Value("${finora.signature.provider:MOCK}") String signatureProvider,
            Environment environment) {
        this(point, Duration.parse(duration.trim()), signatureProvider,
                environment.acceptsProfiles(Profiles.of("local")));
    }

    E2EPausePoints(String point, Duration duration, String signatureProvider, boolean localProfile) {
        this.point = point == null ? "" : point.trim();
        this.duration = duration == null ? Duration.ZERO : duration;
        if (enabled() && (!"MOCK".equalsIgnoreCase(signatureProvider) || !localProfile)) {
            throw new IllegalStateException("finora.e2e.pause-point chỉ dùng với chữ ký MOCK và profile local");
        }
    }

    public static E2EPausePoints disabled() {
        return new E2EPausePoints("", Duration.ZERO, "MOCK", false);
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
