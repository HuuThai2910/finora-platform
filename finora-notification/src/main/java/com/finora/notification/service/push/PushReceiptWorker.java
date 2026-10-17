package com.finora.notification.service.push;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.config.PushProviderType;
import com.finora.notification.integration.push.PushReceipt;
import com.finora.notification.integration.push.PushSender;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Đọc receipt của Expo cho ticket đã gửi (mặc định sau 15 phút): xác nhận đã chuyển tới APNs/FCM, hoặc phát hiện
 * {@code DeviceNotRegistered} muộn để thu hồi token. Không có receipt sau số lần đọc tối đa thì giữ SENT và thôi hỏi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushReceiptWorker {

    private final PushDispatchService dispatch;
    private final PushSender sender;
    private final NotificationProperties properties;

    @Scheduled(fixedDelayString = "${finora.notification.push.receipt-worker-delay:60000}",
            initialDelayString = "${finora.notification.push.receipt-initial-delay:30000}")
    public void scheduled() {
        runOnce();
    }

    public int runOnce() {
        if (properties.push().provider() == PushProviderType.DISABLED) return 0;
        List<PushDispatchService.ReceiptCheck> checks = dispatch.claimReceiptChecks(sender.provider().storageCode());
        if (checks.isEmpty()) return 0;
        Map<String, PushReceipt> receipts;
        try {
            receipts = sender.receipts(checks.stream().map(PushDispatchService.ReceiptCheck::ticketId).toList());
        } catch (RuntimeException exception) {
            log.warn("Đọc receipt push lỗi, thử lại ở lượt sau: provider={}, size={}, exception={}",
                    sender.provider(), checks.size(), exception.getClass().getSimpleName());
            receipts = Map.of();
        }
        dispatch.recordReceipts(checks, receipts);
        log.info("Lượt đọc receipt push: provider={}, checked={}, received={}", sender.provider(), checks.size(),
                receipts.size());
        return checks.size();
    }
}
