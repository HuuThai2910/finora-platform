package com.finora.investment.domain.orderbook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Sổ lệnh của một đợt gọi vốn.
 *
 * <p>Dòng này là điểm khóa: mọi thao tác đặt, khớp, huỷ của cùng sổ đều {@code SELECT ... FOR
 * UPDATE} dòng này trước, nên chạy lần lượt và kết quả khớp không phụ thuộc may rủi. Không dùng
 * {@code @Version}: khóa bi quan đã xếp hàng các thao tác, khóa lạc quan chỉ làm người đến sau
 * thất bại vô ích.</p>
 */
@Entity
@Table(name = "order_books")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderBook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id", nullable = false, unique = true, updatable = false)
    private Long listingId;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private Long loanId;

    @Column(name = "last_sequence", nullable = false)
    private Long lastSequence;

    @Column(name = "last_trade_permille")
    private Integer lastTradePermille;

    @Column(name = "last_trade_at")
    private Instant lastTradeAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Tăng bộ đếm và trả về giá trị mới; gọi khi đang giữ khóa của sổ. */
    public long nextSequence(Instant now) {
        lastSequence = lastSequence + 1;
        updatedAt = now;
        return lastSequence;
    }

    public void recordTrade(int pricePermille, Instant now) {
        lastTradePermille = pricePermille;
        lastTradeAt = now;
        updatedAt = now;
    }
}
