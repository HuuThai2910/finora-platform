package com.finora.common.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bộ đếm theo cửa sổ cố định, lưu trong bộ nhớ của một instance (D40). Đủ cho một instance mỗi service như hiện tại;
 * chạy nhiều instance thì mỗi instance đếm riêng nên giới hạn thực tế nhân theo số instance — cần store chung
 * (R8B/R9) trước khi scale ngang.
 */
public class InMemoryRateLimiter {

    /** Dọn cửa sổ đã hết khi số khóa vượt ngưỡng này, để bộ nhớ không tăng mãi theo số người gọi. */
    static final int CLEANUP_THRESHOLD = 10_000;

    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Kết quả một lần thử: được phép, hoặc phải chờ {@code retryAfter}. */
    public record Decision(boolean allowed, Duration retryAfter) {
    }

    public Decision tryAcquire(String key, int limit, Duration window) {
        Instant now = clock.instant();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(existing -> !existing.endsAt().isAfter(now));
        }
        Window current = windows.compute(key, (ignored, existing) -> {
            if (existing == null || !existing.endsAt().isAfter(now)) {
                return new Window(now.plus(window), 1);
            }
            return new Window(existing.endsAt(), existing.count() + 1);
        });
        if (current.count() <= limit) {
            return new Decision(true, Duration.ZERO);
        }
        return new Decision(false, Duration.between(now, current.endsAt()));
    }

    int trackedKeys() {
        return windows.size();
    }

    private record Window(Instant endsAt, int count) {
    }
}
