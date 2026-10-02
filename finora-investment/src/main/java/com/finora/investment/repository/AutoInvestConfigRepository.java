package com.finora.investment.repository;

import com.finora.investment.domain.autoinvest.AutoInvestConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AutoInvestConfigRepository extends JpaRepository<AutoInvestConfig, Long> {

    Optional<AutoInvestConfig> findByInvestorId(String investorId);

    /**
     * Cấu hình đang bật từ trước khi listing mở và chưa được xét cho listing này, theo thứ tự
     * hàng chờ. Lọc hạng/lãi suất/kỳ hạn ở {@link AutoInvestConfig#accepts} vì hạng lưu dạng CSV.
     */
    @Query("""
            SELECT c FROM AutoInvestConfig c
            WHERE c.enabled = true
              AND c.enabledAt <= :openedAt
              AND c.minAnnualRate <= :annualInterestRate
              AND c.maxTermMonths >= :termMonths
              AND NOT EXISTS (
                  SELECT 1 FROM AutoInvestMatch m
                  WHERE m.investorId = c.investorId AND m.listingId = :listingId)
            ORDER BY c.enabledAt ASC, c.id ASC
            """)
    List<AutoInvestConfig> findCandidates(
            @Param("listingId") Long listingId,
            @Param("openedAt") Instant openedAt,
            @Param("annualInterestRate") BigDecimal annualInterestRate,
            @Param("termMonths") Integer termMonths);
}
