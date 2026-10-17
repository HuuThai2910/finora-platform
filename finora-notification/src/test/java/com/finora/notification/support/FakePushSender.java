package com.finora.notification.support;

import com.finora.notification.config.PushProviderType;
import com.finora.notification.integration.push.PushMessage;
import com.finora.notification.integration.push.PushOutcome;
import com.finora.notification.integration.push.PushReceipt;
import com.finora.notification.integration.push.PushSender;
import com.finora.notification.integration.push.PushTicket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider giả cho integration test: kết quả theo token được lập trình trước, ghi lại thông điệp đã nhận để kiểm nội
 * dung đã che. Mặc định nhận hết (ticket {@code fake-*}) và receipt xác nhận.
 */
public class FakePushSender implements PushSender {

    private final Map<String, PushTicket> ticketByToken = new ConcurrentHashMap<>();
    private final Map<String, PushReceipt> receiptByTicket = new ConcurrentHashMap<>();
    private final List<PushMessage> sent = Collections.synchronizedList(new ArrayList<>());

    public void reset() {
        ticketByToken.clear();
        receiptByTicket.clear();
        sent.clear();
    }

    public void answer(String token, PushTicket ticket) {
        ticketByToken.put(token, ticket);
    }

    public void receipt(String ticketId, PushReceipt receipt) {
        receiptByTicket.put(ticketId, receipt);
    }

    public List<PushMessage> sent() {
        return List.copyOf(sent);
    }

    @Override
    public PushProviderType provider() {
        return PushProviderType.LOG;
    }

    @Override
    public List<PushTicket> send(List<PushMessage> messages) {
        sent.addAll(messages);
        return messages.stream()
                .map(message -> ticketByToken.getOrDefault(message.token(),
                        PushTicket.accepted("fake-" + UUID.randomUUID())))
                .toList();
    }

    @Override
    public Map<String, PushReceipt> receipts(List<String> ticketIds) {
        Map<String, PushReceipt> result = new java.util.LinkedHashMap<>();
        for (String ticketId : ticketIds) {
            result.put(ticketId, receiptByTicket.getOrDefault(ticketId, new PushReceipt(PushOutcome.ACCEPTED, null)));
        }
        return result;
    }
}
