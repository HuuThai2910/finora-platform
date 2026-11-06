package com.finora.notification.service.push.impl;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.config.PushProviderType;
import com.finora.notification.integration.push.client.PushSender;
import com.finora.notification.integration.push.contract.PushOutcome;
import com.finora.notification.integration.push.contract.PushTicket;
import com.finora.notification.service.push.PushDispatchService;
import com.finora.notification.service.push.PushDispatchWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Worker gửi push (D62): mỗi lượt claim tối đa một lô (100, giới hạn Expo), gửi một request, ghi kết quả. Lỗi bất ngờ
 * của provider được coi là lỗi tạm thời cho cả lô để retry/DEAD theo chính sách, không làm worker dừng.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushDispatchWorkerImpl implements PushDispatchWorker {

    private final PushDispatchService pushDispatchService;
    private final PushSender pushSender;
    private final NotificationProperties notificationProperties;

    @Override
    @Scheduled(fixedDelayString = "${finora.notification.push.dispatch-delay:5000}",
            initialDelayString = "${finora.notification.push.dispatch-initial-delay:15000}")
    public void scheduled() {
        runOnce();
    }

    @Override
    public int runOnce() {
        if (notificationProperties.push().provider() == PushProviderType.DISABLED) return 0;
        List<PushDispatchService.ClaimedPush> claimed =
                pushDispatchService.claimDue(pushSender.provider().storageCode());
        if (claimed.isEmpty()) return 0;
        List<PushTicket> tickets;
        try {
            tickets = pushSender.send(claimed.stream().map(PushDispatchService.ClaimedPush::message).toList());
        } catch (RuntimeException exception) {
            log.warn("Push provider lỗi bất ngờ, cả lô thử lại sau: provider={}, size={}, exception={}",
                    pushSender.provider(), claimed.size(), exception.getClass().getSimpleName());
            tickets = Collections.nCopies(claimed.size(),
                    PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "PUSH_SENDER_ERROR"));
        }
        PushDispatchService.TicketSummary summary = pushDispatchService.recordTickets(claimed, tickets);
        log.info("Lượt gửi push: provider={}, claimed={}, accepted={}, retried={}, failed={}, dead={}",
                pushSender.provider(), claimed.size(), summary.accepted(), summary.retried(), summary.failed(),
                summary.dead());
        return claimed.size();
    }
}
