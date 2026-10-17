package com.finora.notification.service.push;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.config.PushProviderType;
import com.finora.notification.integration.push.PushOutcome;
import com.finora.notification.integration.push.PushSender;
import com.finora.notification.integration.push.PushTicket;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker gửi push (D62): mỗi lượt claim tối đa một lô (100, giới hạn Expo), gửi một request, ghi kết quả. Lỗi bất ngờ
 * của provider được coi là lỗi tạm thời cho cả lô để retry/DEAD theo chính sách, không làm worker dừng.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushDispatchWorker {

    private final PushDispatchService dispatch;
    private final PushSender sender;
    private final NotificationProperties properties;

    @Scheduled(fixedDelayString = "${finora.notification.push.dispatch-delay:5000}",
            initialDelayString = "${finora.notification.push.dispatch-initial-delay:15000}")
    public void scheduled() {
        runOnce();
    }

    /** @return số push đã thử gửi trong lượt này */
    public int runOnce() {
        if (properties.push().provider() == PushProviderType.DISABLED) return 0;
        List<PushDispatchService.ClaimedPush> claimed = dispatch.claimDue(sender.provider().storageCode());
        if (claimed.isEmpty()) return 0;
        List<PushTicket> tickets;
        try {
            tickets = sender.send(claimed.stream().map(PushDispatchService.ClaimedPush::message).toList());
        } catch (RuntimeException exception) {
            log.warn("Push provider lỗi bất ngờ, cả lô thử lại sau: provider={}, size={}, exception={}",
                    sender.provider(), claimed.size(), exception.getClass().getSimpleName());
            tickets = Collections.nCopies(claimed.size(),
                    PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "PUSH_SENDER_ERROR"));
        }
        PushDispatchService.TicketSummary summary = dispatch.recordTickets(claimed, tickets);
        log.info("Lượt gửi push: provider={}, claimed={}, accepted={}, retried={}, failed={}, dead={}",
                sender.provider(), claimed.size(), summary.accepted(), summary.retried(), summary.failed(),
                summary.dead());
        return claimed.size();
    }
}
