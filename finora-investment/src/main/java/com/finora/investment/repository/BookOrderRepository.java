package com.finora.investment.repository;

import com.finora.investment.domain.orderbook.BookOrder;
import com.finora.investment.domain.orderbook.BookOrderStatus;
import com.finora.investment.domain.orderbook.OrderSide;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookOrderRepository extends JpaRepository<BookOrder, Long> {

    Optional<BookOrder> findByOrderReference(String orderReference);

    Optional<BookOrder> findByInvestorIdAndIdempotencyKey(String investorId, String idempotencyKey);

    /**
     * Lệnh bán đang nằm chờ mà một lệnh mua giá {@code limitPermille} khớp được: giá thấp nhất
     * trước, cùng giá thì vào sổ trước đi trước. Đi theo index một phần
     * {@code idx_order_book_orders_book_side_price_sequence}.
     */
    @Query("""
            SELECT o FROM BookOrder o
            WHERE o.orderBookId = :bookId AND o.side = 'ASK'
              AND o.status IN ('OPEN', 'PARTIALLY_FILLED')
              AND o.pricePermille <= :limitPermille
            ORDER BY o.pricePermille ASC, o.sequence ASC
            """)
    List<BookOrder> findCrossingAsks(
            @Param("bookId") Long bookId, @Param("limitPermille") int limitPermille, Pageable pageable);

    /** Đối xứng với {@link #findCrossingAsks}: giá cao nhất trước, cùng giá thì vào sổ trước. */
    @Query("""
            SELECT o FROM BookOrder o
            WHERE o.orderBookId = :bookId AND o.side = 'BID'
              AND o.status IN ('OPEN', 'PARTIALLY_FILLED')
              AND o.pricePermille >= :limitPermille
            ORDER BY o.pricePermille DESC, o.sequence ASC
            """)
    List<BookOrder> findCrossingBids(
            @Param("bookId") Long bookId, @Param("limitPermille") int limitPermille, Pageable pageable);

    /** Độ sâu phía mua, gộp theo mức giá, giá cao nhất trước. */
    @Query("""
            SELECT new com.finora.investment.repository.PriceLevelView(
                o.pricePermille, SUM(o.quantity - o.filledQuantity), COUNT(o))
            FROM BookOrder o
            WHERE o.orderBookId = :bookId AND o.side = 'BID' AND o.status IN ('OPEN', 'PARTIALLY_FILLED')
            GROUP BY o.pricePermille
            ORDER BY o.pricePermille DESC
            """)
    List<PriceLevelView> findBidLevels(@Param("bookId") Long bookId, Pageable pageable);

    /** Độ sâu phía bán, gộp theo mức giá, giá thấp nhất trước. */
    @Query("""
            SELECT new com.finora.investment.repository.PriceLevelView(
                o.pricePermille, SUM(o.quantity - o.filledQuantity), COUNT(o))
            FROM BookOrder o
            WHERE o.orderBookId = :bookId AND o.side = 'ASK' AND o.status IN ('OPEN', 'PARTIALLY_FILLED')
            GROUP BY o.pricePermille
            ORDER BY o.pricePermille ASC
            """)
    List<PriceLevelView> findAskLevels(@Param("bookId") Long bookId, Pageable pageable);

    /**
     * Giá mua cao nhất và giá bán thấp nhất của nhiều sổ trong một truy vấn, cho danh sách sổ —
     * tránh mỗi dòng danh sách một truy vấn.
     */
    @Query("""
            SELECT new com.finora.investment.repository.BestPriceView(
                o.orderBookId, o.side,
                MAX(o.pricePermille), MIN(o.pricePermille))
            FROM BookOrder o
            WHERE o.orderBookId IN :bookIds AND o.status IN ('OPEN', 'PARTIALLY_FILLED')
            GROUP BY o.orderBookId, o.side
            """)
    List<BestPriceView> findBestPrices(@Param("bookIds") Collection<Long> bookIds);

    Page<BookOrder> findByInvestorIdOrderByCreatedAtDesc(String investorId, Pageable pageable);

    Page<BookOrder> findByInvestorIdAndStatusInOrderByCreatedAtDesc(
            String investorId, Collection<BookOrderStatus> statuses, Pageable pageable);

    List<BookOrder> findByListingIdAndInvestorIdAndStatusInOrderByCreatedAtDesc(
            Long listingId, String investorId, Collection<BookOrderStatus> statuses);

    /**
     * Lệnh mua đã kết thúc, còn tiền giữ chưa nhả, và mọi lần khớp của nó đã thanh toán xong.
     *
     * <p>Phải đợi thanh toán xong mới nhả: Payment nhả <em>toàn bộ</em> phần đang giữ còn lại,
     * nhả sớm thì lần thanh toán đang chờ sẽ không còn tiền để chuyển. Lần khớp {@code FAILED}
     * cũng chặn việc nhả — để nguyên cho đối soát tay.</p>
     */
    @Query("""
            SELECT o FROM BookOrder o
            WHERE o.side = 'BID' AND o.status IN ('FILLED', 'CANCELLED')
              AND o.holdReference IS NOT NULL AND o.holdReleasedAt IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM BookTrade t
                  WHERE t.bidOrderId = o.id AND t.settlementStatus <> 'SETTLED')
            ORDER BY o.id ASC
            """)
    List<BookOrder> findReleasePending(Pageable pageable);

    boolean existsByOrderBookIdAndSideAndStatusIn(
            Long orderBookId, OrderSide side, Collection<BookOrderStatus> statuses);

    /** Lệnh còn nằm trong sổ của mọi sổ, gộp theo chiều: số lệnh và số Note chờ khớp. */
    @Query("""
            SELECT new com.finora.investment.repository.RestingTotalsView(
                o.side, COUNT(o), SUM(o.quantity - o.filledQuantity))
            FROM BookOrder o
            WHERE o.status IN ('OPEN', 'PARTIALLY_FILLED')
            GROUP BY o.side
            """)
    List<RestingTotalsView> sumRestingBySide();
}
