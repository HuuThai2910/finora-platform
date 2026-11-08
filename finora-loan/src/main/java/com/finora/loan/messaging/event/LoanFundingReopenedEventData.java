package com.finora.loan.messaging.event;

import java.time.Instant;
import java.util.List;

/**
 * Payload {@code LoanFundingReopened.v1}: một hoặc nhiều nhà đầu tư chưa ký rời hợp đồng nhiều bên (từ chối hoặc hết
 * hạn ký) nên khoản vay quay lại sàn gọi phần vốn còn thiếu.
 *
 * <p>Investment đối chiếu {@code allocationVersion/allocationHash} với bản đã khóa, hủy đúng các commitment trong
 * {@code releasedCommitmentIds} (worker nhả tiền giữ) rồi mở lại listing. Các commitment còn lại giữ nguyên tiền giữ
 * và vị trí trong hợp đồng. Không mang PII hay lý do tự do.</p>
 *
 * @param reasonCode            {@code INVESTOR_DECLINED} hoặc {@code LENDER_SIGNATURE_EXPIRED}
 * @param releasedInvestorIds   ID logic nhà đầu tư vừa rời hợp đồng, để Notification báo tiền giữ được trả lại
 * @param retainedInvestorIds   ID logic nhà đầu tư còn trong hợp đồng, để Notification báo phần vốn được giữ nguyên
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
        String releasedAmount,
        List<String> releasedInvestorIds,
        List<String> retainedInvestorIds,
        Instant reopenedAt,
        String borrowerId
) {
}
