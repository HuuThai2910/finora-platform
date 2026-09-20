package com.finora.investment.repository;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.domain.listing.MarketListing;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketListingRepository extends JpaRepository<MarketListing, Long> {

    /**
     * Đọc listing kèm khóa ghi để chống overfund.
     *
     * <p>Hai nhà đầu tư bấm mua cùng lúc phần vốn cuối cùng sẽ bị database xếp hàng tại đây:
     * người sau chỉ đọc được {@code committedAmount} sau khi người trước đã commit, nên
     * kiểm tra "còn đủ chỗ không" luôn nhìn thấy số liệu mới nhất. Nếu chỉ dùng optimistic
     * {@code @Version}, người sau sẽ thất bại bằng exception và mất lệnh — dùng khóa bi quan
     * cho luồng tiền là lựa chọn có chủ ý (transaction-concurrency.md).</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM MarketListing l WHERE l.id = :id")
    Optional<MarketListing> findByIdForUpdate(@Param("id") Long id);

    Optional<MarketListing> findByLoanId(Long loanId);

    boolean existsByLoanId(Long loanId);

    Page<MarketListing> findByStatus(ListingStatus status, Pageable pageable);

    /**
     * Lọc sàn theo trạng thái, hạng tín dụng và lãi suất tối thiểu; dùng cho bộ lọc trên app.
     *
     * <p>{@code status} để trống nghĩa là lấy mọi trạng thái — màn quản trị cần thấy cả
     * khoản đã gọi đủ vốn để khóa vốn và phát hành Note, trong khi app nhà đầu tư chỉ
     * quan tâm khoản đang mở.</p>
     */
    @Query("""
            SELECT l FROM MarketListing l
            WHERE (:status IS NULL OR l.status = :status)
              AND (:grade IS NULL OR l.creditGrade = :grade)
              AND (:minRate IS NULL OR l.annualInterestRate >= :minRate)
              AND (:maxTermMonths IS NULL OR l.termMonths <= :maxTermMonths)
            """)
    Page<MarketListing> search(
            @Param("status") ListingStatus status,
            @Param("grade") String grade,
            @Param("minRate") java.math.BigDecimal minRate,
            @Param("maxTermMonths") Integer maxTermMonths,
            Pageable pageable
    );

    /** Listing quá hạn gọi vốn, để worker đóng lại theo lô. */
    @Query("SELECT l FROM MarketListing l WHERE l.status = 'OPEN' AND l.fundingClosesAt <= :now")
    List<MarketListing> findExpired(@Param("now") Instant now, Pageable pageable);
}
