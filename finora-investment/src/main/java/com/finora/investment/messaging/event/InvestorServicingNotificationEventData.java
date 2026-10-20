package com.finora.investment.messaging.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Projection event dành cho Notification. Investment là service biết chủ Note,
 * nên thực hiện fan-out theo investor mà không để Notification gọi ngược N+1.
 */
public record InvestorServicingNotificationEventData(
        String changeType,
        Long loanApplicationId,
        String loanNumber,
        int daysPastDue,
        int debtGroup,
        String amount,
        LocalDate maturityDate,
        boolean externalPushRequired,
        Instant changedAt,
        List<Recipient> recipients
) {
    /**
     * @param amount phần tiền (gốc + lãi + phần phí trả trước nếu có) Note này vừa nhận; chỉ có ở REPAYMENT_CREDITED và
     *               EARLY_SETTLEMENT, các loại khác để null. Notification cộng theo nhà đầu tư để
     *               mỗi người thấy đúng phần của mình thay vì tổng của cả khoản vay.
     */
    public record Recipient(Long noteId, String noteNumber, String investorId, String amount) {}
}
