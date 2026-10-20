package com.finora.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Đồng hồ hệ thống cộng một độ lệch chỉnh được, để integration test đi tới ngày đến hạn mà thời gian vẫn tăng tự nhiên
 * (thứ tự {@code created_at} giữ nguyên). Chỉ dùng trong test.
 */
final class MutableTestClock extends Clock {
    private final ZoneId zone;
    private volatile Duration offset = Duration.ZERO;

    MutableTestClock(ZoneId zone) {
        this.zone = zone;
    }

    void advance(Duration value) {
        offset = offset.plus(value);
    }

    void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId value) {
        MutableTestClock copy = new MutableTestClock(value);
        copy.offset = offset;
        return copy;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }
}
