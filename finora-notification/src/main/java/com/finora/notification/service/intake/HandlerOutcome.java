package com.finora.notification.service.intake;

import com.finora.notification.service.delivery.NotificationDraft;
import java.util.List;

/** Quyết định của handler cho một event. */
public sealed interface HandlerOutcome {

    /** Tạo các thông báo này (rỗng nghĩa là event hợp lệ nhưng không ai cần nhận). */
    record Deliver(List<NotificationDraft> drafts) implements HandlerOutcome {}

    /**
     * Chưa biết đủ người nhận (event khác topic chưa tới). {@code knownDrafts} là phần đã xác định được, chỉ gửi khi hết
     * thời gian chờ để không gửi người vay trước rồi nhà đầu tư sau thành hai lượt lệch nhau.
     */
    record AwaitRecipients(String reason, List<NotificationDraft> knownDrafts) implements HandlerOutcome {}

    /** Cố ý không thông báo (ví dụ DPD tăng một ngày trong cùng nhóm nợ, D61). */
    record Filtered(String reason) implements HandlerOutcome {}

    static HandlerOutcome deliver(List<NotificationDraft> drafts) {
        return new Deliver(List.copyOf(drafts));
    }

    static HandlerOutcome await(String reason, List<NotificationDraft> knownDrafts) {
        return new AwaitRecipients(reason, List.copyOf(knownDrafts));
    }

    static HandlerOutcome filtered(String reason) {
        return new Filtered(reason);
    }
}
