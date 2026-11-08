package com.finora.investment.messaging.event;

import java.time.Instant;
import java.util.List;

/**
 * Payload {@code LoanFundingReopened.v1} từ Loan: nhà đầu tư chưa ký rời hợp đồng (từ chối hoặc hết hạn ký) nên
 * khoản vay quay lại sàn gọi phần vốn còn thiếu. Investment chỉ hủy {@code releasedCommitmentIds}; phần còn lại giữ
 * nguyên tiền giữ. Field thêm sau (danh sách nhà đầu tư cho Notification) không cần ở đây.
 */
public record LoanFundingReopenedEventData(
        Long loanApplicationId,
        String applicationNumber,
        Long listingId,
        Integer fundingRound,
        String contractNumber,
        Long allocationVersion,
        String allocationHash,
        String reasonCode,
        List<Long> releasedCommitmentIds,
        Instant reopenedAt
) {
}
