package com.finora.notification.service.push;

/**
 * Worker gửi push (D62): mỗi lượt claim tối đa một lô (100, giới hạn Expo), gửi một request, ghi kết quả.
 */
public interface PushDispatchWorker {

    /** Lượt định kỳ theo {@code finora.notification.push.dispatch-delay}: chạy {@link #runOnce()}. */
    void scheduled();

    /** @return số push đã thử gửi trong lượt này */
    int runOnce();
}
