package com.finora.notification.service.push;

import com.finora.notification.integration.push.contract.PushMessage;
import com.finora.notification.integration.push.contract.PushReceipt;
import com.finora.notification.integration.push.contract.PushTicket;

import java.util.List;
import java.util.Map;

/**
 * Trạng thái push trong database; provider được gọi bởi worker ở giữa hai transaction (D62): claim lô đến hạn, gửi
 * ngoài transaction, rồi chỉ ghi kết quả khi dòng còn đúng claim token.
 */
public interface PushDispatchService {

    /** Khóa lô push đến hạn và chuyển sang SENDING (tăng lượt thử, đặt lease); thiết bị đã thu hồi thì bỏ dòng đó. */
    List<ClaimedPush> claimDue(String providerCode);

    /** Ghi ticket theo thứ tự lô đã claim, bỏ qua dòng không còn đúng claim token; thiếu ticket là lỗi tạm thời. */
    TicketSummary recordTickets(List<ClaimedPush> claimed, List<PushTicket> tickets);

    /** Khóa lô ticket đến hạn đọc receipt; ticket không thuộc provider hiện tại thì thôi hỏi. */
    List<ReceiptCheck> claimReceiptChecks(String providerCode);

    /** Ghi receipt đã đọc; ticket chưa có receipt thì thôi hỏi khi đã đọc đủ số lần tối đa. */
    void recordReceipts(List<ReceiptCheck> checks, Map<String, PushReceipt> receipts);

    /** Một lượt gửi đã claim; {@code attempt} là claim token. */
    record ClaimedPush(long deliveryId, int attempt, PushMessage message) {}

    /** Một ticket cần đọc receipt. */
    record ReceiptCheck(long deliveryId, String ticketId) {}

    /** Đếm kết quả một lượt gửi để log. */
    record TicketSummary(int accepted, int retried, int failed, int dead) {}
}
