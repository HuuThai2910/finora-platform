package com.finora.investment.repository;

import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.investment.domain.order.entity.InvestmentCommitment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InvestmentCommitmentRepository extends JpaRepository<InvestmentCommitment, Long> {

    Optional<InvestmentCommitment> findByOrderId(Long orderId);

    List<InvestmentCommitment> findByListingIdAndStatus(Long listingId, CommitmentStatus status);

    List<InvestmentCommitment> findByListingIdAndStatusOrderByIdAsc(Long listingId, CommitmentStatus status);

    /**
     * Toàn bộ phần vốn của một khoản vay, mới nhất trước.
     *
     * <p>Dùng cho màn quản trị xem ai đã góp. Lấy cả phần đã hủy để người quản trị thấy
     * được lịch sử đầy đủ, thay vì tưởng một lệnh biến mất không dấu vết.</p>
     */
    List<InvestmentCommitment> findByListingIdOrderByCreatedAtDesc(Long listingId);

    List<InvestmentCommitment> findByInvestorIdAndStatusIn(String investorId, List<CommitmentStatus> statuses);

    /**
     * Số nhà đầu tư của nhiều listing trong một câu {@code GROUP BY}, cho danh sách gọi vốn không phải đọc từng dòng.
     *
     * <p>Đếm phần vốn còn tính vào tổng ({@code countedStatuses}: ACTIVE, FINALIZED) và phần vốn bị hủy vì chính listing
     * đóng/hủy (lệnh mang một trong {@code closingReasons}), để khoản đã đóng/hủy vẫn cho biết bao nhiêu người đã góp
     * trước khi được hoàn tiền. Listing không có ai góp không có dòng nào.</p>
     */
    @Query("""
            select new com.finora.investment.repository.ListingInvestorCountView(c.listingId, count(distinct c.investorId))
            from InvestmentCommitment c, InvestmentOrder o
            where o.id = c.orderId
              and c.listingId in :listingIds
              and (c.status in :countedStatuses
                   or (c.status = :cancelledStatus and o.rejectedReasonCode in :closingReasons))
            group by c.listingId
            """)
    List<ListingInvestorCountView> countInvestorsByListingIds(
            @Param("listingIds") Collection<Long> listingIds,
            @Param("countedStatuses") Collection<CommitmentStatus> countedStatuses,
            @Param("cancelledStatus") CommitmentStatus cancelledStatus,
            @Param("closingReasons") Collection<String> closingReasons);
}
