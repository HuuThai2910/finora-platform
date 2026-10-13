package com.finora.investment.repository;

import com.finora.common.enums.investment.OrderStatus;
import com.finora.investment.domain.order.InvestmentOrder;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestmentOrderRepository extends JpaRepository<InvestmentOrder, Long> {

    /** Tra cứu theo khóa idempotency để lần gửi lại trả về đúng lệnh cũ. */
    Optional<InvestmentOrder> findByInvestorIdAndIdempotencyKey(String investorId, String idempotencyKey);

    Optional<InvestmentOrder> findByOrderReference(String orderReference);

    /** Nhà đầu tư đã có lệnh ở một trong các trạng thái này trên listing chưa — Auto-Invest dùng để không đặt chồng. */
    boolean existsByListingIdAndInvestorIdAndStatusIn(
            Long listingId, String investorId, Collection<OrderStatus> statuses);

    Page<InvestmentOrder> findByInvestorIdOrderByCreatedAtDesc(String investorId, Pageable pageable);

    /**
     * Lệnh kẹt ở {@code PENDING_FUNDS} từ trước {@code before}: lần giữ tiền không có kết quả chắc
     * chắn và chưa ai gửi lại cùng khóa để làm tiếp.
     */
    @Query("""
            SELECT o FROM InvestmentOrder o
            WHERE o.status = 'PENDING_FUNDS' AND o.updatedAt < :before
            ORDER BY o.id ASC
            """)
    List<InvestmentOrder> findStalePending(@Param("before") Instant before, Pageable pageable);

    /**
     * Lệnh đã kết thúc (huỷ, hết hạn gọi vốn, bị từ chối sau khi đã giữ tiền) mà khoản giữ chưa
     * nhả được về ví — lời gọi nhả thất bại hoặc được dời cho worker. Đi theo index một phần
     * {@code ix_order_release_pending}.
     */
    @Query("""
            SELECT o FROM InvestmentOrder o
            WHERE o.status IN ('CANCELLED', 'REJECTED')
              AND o.paymentHoldReference IS NOT NULL AND o.paymentReleasedAt IS NULL
            ORDER BY o.id ASC
            """)
    List<InvestmentOrder> findReleasePending(Pageable pageable);
}
