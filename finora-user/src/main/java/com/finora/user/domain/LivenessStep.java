package com.finora.user.domain;

/**
 * Một bước của thử thách liveness: giữ tư thế {@code action} trong {@code holdMs} mili giây.
 * Khoảng cách giữa các đỉnh hành động phải khớp lịch này, nên video quay sẵn không qua được.
 */
public record LivenessStep(LivenessAction action, int holdMs) {
}
