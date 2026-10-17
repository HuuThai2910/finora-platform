package com.finora.notification.integration.push;

import com.finora.notification.config.PushProviderType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Bản GIẢ LẬP của push provider (mặc định, D62): không gọi Expo và không có gì tới thiết bị. Dùng khi chưa có EAS project
 * và thiết bị thật để luồng thông báo, preference, retry và receipt vẫn chạy trọn vẹn trong demo/E2E.
 * <p>
 * Ticket mang tiền tố {@code mock-} và delivery ghi provider {@code LOG_MOCK}, nên không thể nhầm với lần gửi Expo
 * thật khi đối soát. Log chỉ có ID và tiêu đề đã che, không có token.
 */
@Slf4j
public class LoggingPushSender implements PushSender {

    static final String TICKET_PREFIX = "mock-";

    @Override
    public PushProviderType provider() {
        return PushProviderType.LOG;
    }

    @Override
    public List<PushTicket> send(List<PushMessage> messages) {
        return messages.stream().map(message -> {
            log.info("PUSH GIẢ LẬP (không gửi tới Expo/thiết bị): deliveryId={}, tokenRef={}, title='{}'",
                    message.deliveryId(), message.tokenRef(), message.title());
            return PushTicket.accepted(TICKET_PREFIX + UUID.randomUUID());
        }).toList();
    }

    @Override
    public Map<String, PushReceipt> receipts(List<String> ticketIds) {
        Map<String, PushReceipt> receipts = new LinkedHashMap<>();
        for (String ticketId : ticketIds) {
            if (ticketId != null && ticketId.startsWith(TICKET_PREFIX)) {
                receipts.put(ticketId, new PushReceipt(PushOutcome.ACCEPTED, null));
            }
        }
        return receipts;
    }
}
