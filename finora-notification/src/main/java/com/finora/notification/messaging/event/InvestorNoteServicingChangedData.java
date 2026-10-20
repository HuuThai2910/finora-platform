package com.finora.notification.messaging.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code InvestorNoteServicingChanged.v1} từ Investment: Investment biết chủ Note hiện tại nên tự fan-out danh sách
 * người nhận; Notification không gọi ngược Investment theo từng Note.
 *
 * @param externalPushRequired Investment đánh dấu mốc đáng push (tất toán, cơ cấu, đổi nhóm nợ ≥ 2, khắc phục);
 *                             Notification tôn trọng cờ này và bỏ cả tin trong app của các ngày DPD tăng thêm (B60)
 */
public record InvestorNoteServicingChangedData(String changeType, Long loanApplicationId,
        String loanNumber, int daysPastDue, int debtGroup, String amount,
        LocalDate maturityDate, boolean externalPushRequired, Instant changedAt,
        List<Recipient> recipients) {
    /** {@code amount} là phần tiền của riêng Note này; event cũ không có trường này thì null. */
    public record Recipient(Long noteId, String noteNumber, String investorId, String amount) {}
}
