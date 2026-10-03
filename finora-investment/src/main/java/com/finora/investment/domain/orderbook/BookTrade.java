package com.finora.investment.domain.orderbook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Một lần khớp giữa một lệnh mua và một lệnh bán, gồm một hoặc nhiều Note.
 *
 * <p>Phần nội dung giao dịch là bất biến ({@code updatable = false}); chỉ các cột thanh toán đổi
 * khi worker gọi Payment xong. Không có setter để không ai sửa giá hay số tiền sau khi khớp.</p>
 */
@Entity
@Table(name = "order_book_trades")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_reference", nullable = false, length = 50, unique = true, updatable = false)
    private String tradeReference;

    @Column(name = "order_book_id", nullable = false, updatable = false)
    private Long orderBookId;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Column(name = "bid_order_id", nullable = false, updatable = false)
    private Long bidOrderId;

    @Column(name = "ask_order_id", nullable = false, updatable = false)
    private Long askOrderId;

    @Column(name = "buyer_id", nullable = false, length = 100, updatable = false)
    private String buyerId;

    @Column(name = "seller_id", nullable = false, length = 100, updatable = false)
    private String sellerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "aggressor_side", nullable = false, length = 4, updatable = false)
    private OrderSide aggressorSide;

    @Column(name = "price_permille", nullable = false, updatable = false)
    private Integer pricePermille;

    @Column(nullable = false, updatable = false)
    private Integer quantity;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "platform_fee", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal platformFee;

    @Column(name = "seller_proceeds", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal sellerProceeds;

    @Column(name = "outstanding_total", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal outstandingTotal;

    @Column(nullable = false, updatable = false)
    private Boolean defaulted;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "settlement_status", nullable = false, length = 20)
    private SettlementStatus settlementStatus;

    @Column(name = "settlement_attempts", nullable = false)
    private Integer settlementAttempts;

    @Column(name = "next_settlement_at")
    private Instant nextSettlementAt;

    @Column(name = "last_settlement_error", length = 100)
    private String lastSettlementError;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "executed_at", nullable = false, updatable = false)
    private Instant executedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void markSettled(Instant now) {
        if (settlementStatus == SettlementStatus.SETTLED) {
            return;
        }
        settlementStatus = SettlementStatus.SETTLED;
        settledAt = now;
        nextSettlementAt = null;
        lastSettlementError = null;
        updatedAt = now;
    }

    /** Lỗi tạm thời: hẹn lần thử sau theo backoff, giữ {@code PENDING}. */
    public void scheduleRetry(String errorCode, Duration backoff, Instant now) {
        settlementAttempts = settlementAttempts + 1;
        lastSettlementError = errorCode;
        nextSettlementAt = now.plus(backoff);
        updatedAt = now;
    }

    /** Payment từ chối hẳn: dừng retry, chờ đối soát tay. */
    public void markFailed(String errorCode, Instant now) {
        settlementAttempts = settlementAttempts + 1;
        settlementStatus = SettlementStatus.FAILED;
        lastSettlementError = errorCode;
        nextSettlementAt = null;
        updatedAt = now;
    }
}
