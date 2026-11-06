package com.finora.notification.service.push;

/**
 * Worker đọc receipt của Expo cho ticket đã gửi: xác nhận đã chuyển tới APNs/FCM, hoặc phát hiện
 * {@code DeviceNotRegistered} muộn để thu hồi token.
 */
public interface PushReceiptWorker {

    /** Lượt định kỳ theo {@code finora.notification.push.receipt-worker-delay}: chạy {@link #runOnce()}. */
    void scheduled();

    /** @return số ticket đã hỏi receipt trong lượt này */
    int runOnce();
}
