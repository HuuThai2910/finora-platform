package com.finora.notification.service.intake;

/**
 * Worker thử lại event đang chờ người nhận (D60), mỗi event một transaction riêng để một dòng lỗi không chặn cả lô.
 */
public interface DeferredEventWorker {

    /** Lượt định kỳ theo {@code finora.notification.deferred.worker-delay}: chạy {@link #runOnce()}. */
    void scheduled();

    /** @return số event chờ đã thử lại xong (không lỗi) trong lượt này */
    int runOnce();
}
