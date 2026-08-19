package com.finora.investment.repository;

import com.finora.investment.domain.InvestmentOrder;
import com.finora.investment.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvestmentOrderRepository extends JpaRepository<InvestmentOrder, Long> {

    Page<InvestmentOrder> findByInvestorId(Long investorId, Pageable pageable);

    /** Lệnh chưa khớp hết, theo thứ tự FIFO (price-time priority). */
    List<InvestmentOrder> findByStatusInOrderByCreatedAtAsc(List<OrderStatus> statuses);
}
