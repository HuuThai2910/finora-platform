package com.finora.notification.messaging.event;

import java.time.Instant;
import java.util.List;

/**
 * Payload gọi vốn mà Notification đọc. Chỉ khai báo field cần dùng (Jackson bỏ qua field khác); field ghi
 * "optional, chờ producer" chưa có trong event hiện hành, Notification đọc ngay khi producer bổ sung (plan W3F, mục P).
 */
public final class LoanFundingEvents {

    private LoanFundingEvents() {
    }

    /** {@code LoanFundingRequested.v1} (Loan). {@code borrowerId} optional từ 2026-10-11, producer cũ để null. */
    public record FundingRequested(Long loanApplicationId, String applicationNumber, Integer fundingRound,
            String targetAmount, String borrowerId) {}

    /** {@code LoanFullyFunded.v2} (Investment): allocation bất biến, nguồn danh sách nhà đầu tư. */
    public record FullyFunded(Long loanApplicationId, String applicationNumber, Integer fundingRound,
            String fundedAmount, Long allocationVersion, List<Allocation> allocations) {}

    public record Allocation(Long commitmentId, String investorId, String amount) {}

    /** {@code LoanFundingExpired.v1} (Investment). {@code investorIds} optional, chờ producer (P6). */
    public record FundingExpired(Long loanApplicationId, String applicationNumber, Integer fundingRound,
            String targetAmount, String committedAmount, List<String> investorIds) {}

    /** {@code LoanFundingCancelled.v1} (Loan). {@code borrowerId} optional, chờ producer (P2). */
    public record FundingCancelled(Long loanApplicationId, String applicationNumber, String contractNumber,
            Long allocationVersion, String reasonCode, Instant cancelledAt, String borrowerId) {}
}
