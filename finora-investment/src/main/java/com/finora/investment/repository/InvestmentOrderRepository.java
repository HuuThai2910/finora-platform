package com.finora.investment.repository;

import com.finora.investment.domain.order.InvestmentOrder;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestmentOrderRepository extends JpaRepository<InvestmentOrder, Long> {

    /** Tra cứu theo khóa idempotency để lần gửi lại trả về đúng lệnh cũ. */
    Optional<InvestmentOrder> findByInvestorIdAndIdempotencyKey(String investorId, String idempotencyKey);

    Optional<InvestmentOrder> findByOrderReference(String orderReference);

    Page<InvestmentOrder> findByInvestorIdOrderByCreatedAtDesc(String investorId, Pageable pageable);
}
