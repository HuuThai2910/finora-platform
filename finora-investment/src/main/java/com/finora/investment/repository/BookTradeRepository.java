package com.finora.investment.repository;

import com.finora.investment.domain.orderbook.entity.BookTrade;
import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface BookTradeRepository extends JpaRepository<BookTrade, Long> {

    /** Giao dịch gần nhất của một sổ, cho băng giá trên màn hình. */
    List<BookTrade> findByOrderBookIdOrderByExecutedAtDescIdDesc(Long orderBookId, Pageable pageable);

    /** Khóa một lần khớp theo mã để nhân viên gửi thanh toán lại (chặn hai người bấm cùng lúc). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM BookTrade t WHERE t.tradeReference = :tradeReference")
    java.util.Optional<BookTrade> findByTradeReferenceForUpdate(@Param("tradeReference") String tradeReference);

    java.util.Optional<BookTrade> findByTradeReference(String tradeReference);

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

    /**
     * Ô tìm của bảng lần khớp quản trị: {@code code} (đã viết hoa, dạng {@code %…%}) khớp mã lần khớp hoặc mã hồ sơ/hợp
     * đồng của khoản vay (mã hiển thị lẫn mã tra cứu). {@code status} {@code null} là mọi trạng thái thanh toán.
     */
    @Query("""
            SELECT t FROM BookTrade t
            WHERE (:status IS NULL OR t.settlementStatus = :status)
              AND (UPPER(t.tradeReference) LIKE :code ESCAPE '!'
                   OR t.listingId IN (
                    SELECT l.id FROM MarketListing l
                    WHERE l.applicationCode LIKE :code ESCAPE '!' OR l.contractCode LIKE :code ESCAPE '!'
                       OR l.applicationNumber LIKE :code ESCAPE '!' OR l.contractNumber LIKE :code ESCAPE '!'))
            ORDER BY t.executedAt DESC, t.id DESC
            """)
    Page<BookTrade> search(
            @Param("status") SettlementStatus status,
            @Param("code") String code,
            Pageable pageable);
}
