package com.finora.investment.repository;

import com.finora.investment.domain.AutoInvestConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AutoInvestConfigRepository extends JpaRepository<AutoInvestConfig, Long> {

    Optional<AutoInvestConfig> findByInvestorId(Long investorId);

    /** Config đang bật, sắp theo thời gian tạo (FIFO priority). */
    List<AutoInvestConfig> findByIsActiveTrueOrderByCreatedAtAsc();
}
