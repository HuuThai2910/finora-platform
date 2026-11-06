package com.finora.notification.service.push.impl;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.config.PushProviderType;
import com.finora.notification.integration.push.client.PushSender;
import com.finora.notification.integration.push.contract.PushReceipt;
import com.finora.notification.service.push.PushDispatchService;
import com.finora.notification.service.push.PushReceiptWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Đọc receipt của Expo cho ticket đã gửi (mặc định sau 15 phút): xác nhận đã chuyển tới APNs/FCM, hoặc phát hiện
 * {@code DeviceNotRegistered} muộn để thu hồi token. Không có receipt sau số lần đọc tối đa thì giữ SENT và thôi hỏi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushReceiptWorkerImpl implements PushReceiptWorker {

    private final PushDispatchService pushDispatchService;
    private final PushSender pushSender;
    private final NotificationProperties notificationProperties;

    @Override
    @Scheduled(fixedDelayString = "${finora.notification.push.receipt-worker-delay:60000}",
            initialDelayString = "${finora.notification.push.receipt-initial-delay:30000}")
    public void scheduled() {
        runOnce();
    }

    @Override
    public int runOnce() {
        if (notificationProperties.push().provider() == PushProviderType.DISABLED) return 0;
        List<PushDispatchService.ReceiptCheck> checks =
                pushDispatchService.claimReceiptChecks(pushSender.provider().storageCode());
        if (checks.isEmpty()) return 0;
        Map<String, PushReceipt> receipts;
        try {
            receipts = pushSender.receipts(checks.stream().map(PushDispatchService.ReceiptCheck::ticketId).toList());
        } catch (RuntimeException exception) {
            log.warn("Đọc receipt push lỗi, thử lại ở lượt sau: provider={}, size={}, exception={}",
                    pushSender.provider(), checks.size(), exception.getClass().getSimpleName());
            receipts = Map.of();
        }
        pushDispatchService.recordReceipts(checks, receipts);
        log.info("Lượt đọc receipt push: provider={}, checked={}, received={}", pushSender.provider(), checks.size(),
                receipts.size());
        return checks.size();
    }
}
