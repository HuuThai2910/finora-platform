package com.finora.investment.repository;

import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.investment.domain.order.InvestmentCommitment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestmentCommitmentRepository extends JpaRepository<InvestmentCommitment, Long> {

    Optional<InvestmentCommitment> findByOrderId(Long orderId);

    List<InvestmentCommitment> findByListingIdAndStatus(Long listingId, CommitmentStatus status);

    /**
     * Toàn bộ phần vốn của một khoản vay, mới nhất trước.
     *
     * <p>Dùng cho màn quản trị xem ai đã góp. Lấy cả phần đã hủy để người quản trị thấy
     * được lịch sử đầy đủ, thay vì tưởng một lệnh biến mất không dấu vết.</p>
     */
    List<InvestmentCommitment> findByListingIdOrderByCreatedAtDesc(Long listingId);

    List<InvestmentCommitment> findByInvestorIdAndStatusIn(String investorId, List<CommitmentStatus> statuses);
}
