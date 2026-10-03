package com.finora.investment.repository;

import com.finora.investment.domain.orderbook.OrderBook;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderBookRepository extends JpaRepository<OrderBook, Long> {

    Optional<OrderBook> findByListingId(Long listingId);

    List<OrderBook> findByListingIdIn(Collection<Long> listingIds);

    /**
     * Khóa sổ trước mọi thao tác đặt, khớp, huỷ.
     *
     * <p>Khóa bi quan có chủ ý: hai lệnh đối ứng đến cùng lúc phải chạy lần lượt để thứ tự ưu
     * tiên giá–thời gian được giữ đúng. Khóa lạc quan sẽ để cả hai chạy hết thuật toán khớp rồi
     * một bên thất bại khi ghi, buộc client gửi lại.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM OrderBook b WHERE b.listingId = :listingId")
    Optional<OrderBook> findByListingIdForUpdate(@Param("listingId") Long listingId);

    /**
     * Tạo sổ nếu chưa có, an toàn khi hai request đầu tiên của cùng sổ đến cùng lúc: bên đến sau
     * gặp xung đột unique và không làm gì, rồi cả hai cùng khóa đúng một dòng.
     */
    @Modifying
    @Query(value = """
            INSERT INTO order_books (listing_id, loan_id, last_sequence, created_at, updated_at)
            VALUES (:listingId, :loanId, 0, :now, :now)
            ON CONFLICT (listing_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("listingId") Long listingId, @Param("loanId") Long loanId, @Param("now") Instant now);
}
