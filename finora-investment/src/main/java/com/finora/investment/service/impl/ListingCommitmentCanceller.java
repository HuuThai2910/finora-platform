package com.finora.investment.service.impl;

import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.common.enums.investment.OrderStatus;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.order.InvestmentCommitment;
import com.finora.investment.domain.order.InvestmentOrder;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.InvestmentOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hủy toàn bộ phần vốn đang ACTIVE của một listing và lệnh tương ứng.
 *
 * <p>Dùng chung cho hai tình huống không có giải ngân: hết hạn gọi vốn và Loan hủy đợt gọi vốn đã đủ vốn.
 * Caller phải khóa listing trước; lớp này không gọi Payment. Lệnh chuyển CANCELLED với mã lý do, rồi
 * {@code FundingMaintenanceService.releasePendingHolds} nhả từng khoản giữ idempotent theo mã lệnh.</p>
 */
@Component
@RequiredArgsConstructor
public class ListingCommitmentCanceller {

    private final InvestmentCommitmentRepository commitmentRepository;
    private final InvestmentOrderRepository orderRepository;

    /** @return số phần vốn vừa hủy; tổng vốn đã cam kết của listing giảm tương ứng. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelActive(MarketListing listing, String reasonCode, String reasonDetail, String actor, Instant now) {
        List<InvestmentCommitment> active =
                commitmentRepository.findByListingIdAndStatus(listing.getId(), CommitmentStatus.ACTIVE);
        if (active.isEmpty()) {
            return 0;
        }
        // Đọc lệnh của cả lô trong một truy vấn thay vì mỗi phần vốn một lần.
        Map<Long, InvestmentOrder> orders = orderRepository
                .findAllById(active.stream().map(InvestmentCommitment::getOrderId).toList())
                .stream().collect(Collectors.toMap(InvestmentOrder::getId, Function.identity()));
        BigDecimal released = BigDecimal.ZERO;
        for (InvestmentCommitment commitment : active) {
            commitment.setStatus(CommitmentStatus.CANCELLED);
            commitment.setCancelledAt(now);
            commitment.setUpdatedBy(actor);
            commitment.setUpdatedAt(now);
            released = released.add(commitment.getAmount());

            InvestmentOrder order = orders.get(commitment.getOrderId());
            order.setStatus(OrderStatus.CANCELLED);
            order.setRejectedReasonCode(reasonCode);
            order.setRejectedReasonDetail(reasonDetail);
            order.setUpdatedBy(actor);
            order.setUpdatedAt(now);
        }
        listing.setCommittedAmount(listing.getCommittedAmount().subtract(released));
        return active.size();
    }
}
