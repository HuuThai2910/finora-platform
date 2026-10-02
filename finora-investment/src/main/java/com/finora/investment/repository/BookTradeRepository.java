package com.finora.investment.repository;

import com.finora.investment.domain.orderbook.BookTrade;
import com.finora.investment.domain.orderbook.SettlementStatus;
import org.springframework.data.domain.Page;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookTradeRepository extends JpaRepository<BookTrade, Long> {

    /** Giao dịch gần nhất của một sổ, cho băng giá trên màn hình. */
    List<BookTrade> findByOrderBookIdOrderByExecutedAtDescIdDesc(Long orderBookId, Pageable pageable);

    /** Lần khớp chờ thanh toán đã tới hạn thử lại; cũ nhất trước để không bỏ đói giao dịch nào. */
    @Query("""
            SELECT t FROM BookTrade t
            WHERE t.settlementStatus = 'PENDING'
              AND (t.nextSettlementAt IS NULL OR t.nextSettlementAt <= :now)
            ORDER BY t.id ASC
            """)
    List<BookTrade> findSettlementDue(@Param("now") Instant now, Pageable pageable);

    /** Trang quản trị: mọi lần khớp, mới nhất trước. */
    Page<BookTrade> findAllByOrderByExecutedAtDescIdDesc(Pageable pageable);

    Page<BookTrade> findBySettlementStatusOrderByExecutedAtDescIdDesc(SettlementStatus status, Pageable pageable);

    /** Số lần khớp, tổng tiền và tổng phí theo từng trạng thái thanh toán — một truy vấn cho cả dải số liệu. */
    @Query("""
            SELECT new com.finora.investment.repository.SettlementTotalsView(
                t.settlementStatus, COUNT(t), SUM(t.amount), SUM(t.platformFee))
            FROM BookTrade t
            GROUP BY t.settlementStatus
            """)
    List<SettlementTotalsView> sumBySettlementStatus();
}
