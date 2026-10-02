package com.finora.investment.domain.orderbook;

import com.finora.investment.exception.InvestmentDomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Một lệnh mua hoặc bán Note trên sổ lệnh.
 *
 * <p>Mọi chuyển trạng thái đi qua các method bên dưới, mỗi method chỉ nhận đúng trạng thái nguồn
 * hợp lệ — không có chỗ nào gán {@code status} tuỳ ý. Ràng buộc CHECK của bảng
 * {@code order_book_orders} chặn thêm một lớp ở tầng dữ liệu.</p>
 */
@Entity
@Table(name = "order_book_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_reference", nullable = false, length = 50, unique = true, updatable = false)
    private String orderReference;

    @Column(name = "order_book_id", nullable = false, updatable = false)
    private Long orderBookId;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Column(name = "investor_id", nullable = false, length = 100, updatable = false)
    private String investorId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 4, updatable = false)
    private OrderSide side;

    /** Giá theo phần nghìn của dư nợ gốc còn lại: 975 nghĩa là 97,5%. */
    @Column(name = "price_permille", nullable = false, updatable = false)
    private Integer pricePermille;

    @Column(nullable = false, updatable = false)
    private Integer quantity;

    @Column(name = "filled_quantity", nullable = false)
    private Integer filledQuantity;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private BookOrderStatus status;

    @Column
    private Long sequence;

    @Column(name = "hold_reference", length = 100)
    private String holdReference;

    @Column(name = "hold_amount", precision = 18, scale = 2, updatable = false)
    private BigDecimal holdAmount;

    @Column(name = "hold_consumed", nullable = false, precision = 18, scale = 2)
    private BigDecimal holdConsumed;

    @Column(name = "hold_released_at")
    private Instant holdReleasedAt;

    @Column(name = "acknowledged_default", nullable = false, updatable = false)
    private Boolean acknowledgedDefault;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "cancel_reason", length = 30)
    private CancelReason cancelReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "reject_reason_code", length = 50)
    private String rejectReasonCode;

    @Column(name = "reject_reason_detail", length = 255)
    private String rejectReasonDetail;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public int remainingQuantity() {
        return quantity - filledQuantity;
    }

    /** Phần tiền giữ chưa dùng tới; chỉ có nghĩa với lệnh mua. */
    public BigDecimal holdRemaining() {
        return holdAmount == null ? BigDecimal.ZERO : holdAmount.subtract(holdConsumed);
    }

    /** Lệnh mua giữ tiền xong thì vào sổ và nhận thứ tự thời gian. */
    public void activate(String holdReference, long sequence, Instant now) {
        requireStatus(BookOrderStatus.PENDING_FUNDS, "kích hoạt");
        this.holdReference = holdReference;
        this.sequence = sequence;
        this.status = BookOrderStatus.OPEN;
        this.updatedAt = now;
    }

    public void reject(String reasonCode, String reasonDetail, Instant now) {
        requireStatus(BookOrderStatus.PENDING_FUNDS, "từ chối");
        this.status = BookOrderStatus.REJECTED;
        this.rejectReasonCode = reasonCode;
        this.rejectReasonDetail = reasonDetail;
        this.updatedAt = now;
    }

    /**
     * Ghi nhận một lần khớp {@code quantity} Note.
     *
     * @param consumed số tiền của lần khớp; với lệnh mua được trừ vào phần tiền đang giữ
     */
    public void fill(int quantity, BigDecimal consumed, Instant now) {
        if (!status.isResting()) {
            throw illegalTransition("khớp");
        }
        if (quantity <= 0 || quantity > remainingQuantity()) {
            throw new IllegalArgumentException("Số Note khớp vượt phần còn lại của lệnh " + orderReference);
        }
        if (side == OrderSide.BID) {
            BigDecimal nextConsumed = holdConsumed.add(consumed);
            // Bất biến tiền: không bao giờ chi quá số đã giữ. Vi phạm nghĩa là công thức tính tiền
            // giữ sai, phải dừng hẳn thay vì để Payment phát hiện muộn.
            if (nextConsumed.compareTo(holdAmount) > 0) {
                throw new IllegalStateException("Lần khớp vượt số tiền đã giữ của lệnh " + orderReference);
            }
            holdConsumed = nextConsumed;
        }
        filledQuantity = filledQuantity + quantity;
        status = filledQuantity.equals(this.quantity)
                ? BookOrderStatus.FILLED
                : BookOrderStatus.PARTIALLY_FILLED;
        updatedAt = now;
    }

    public void cancel(CancelReason reason, String actorId, Instant now) {
        if (!status.isResting()) {
            throw illegalTransition("huỷ");
        }
        status = BookOrderStatus.CANCELLED;
        cancelReason = reason;
        cancelledAt = now;
        updatedBy = actorId;
        updatedAt = now;
    }

    public void markHoldReleased(Instant now) {
        holdReleasedAt = now;
        updatedAt = now;
    }

    /** Lệnh mua đã kết thúc mà phần tiền giữ còn lại chưa nhả về ví. */
    public boolean needsHoldRelease() {
        return side == OrderSide.BID
                && (status == BookOrderStatus.FILLED || status == BookOrderStatus.CANCELLED)
                && holdReference != null
                && holdReleasedAt == null;
    }

    private void requireStatus(BookOrderStatus expected, String action) {
        if (status != expected) {
            throw illegalTransition(action);
        }
    }

    private InvestmentDomainException illegalTransition(String action) {
        return InvestmentDomainException.conflict(
                "ORDER_INVALID_STATE",
                "Không thể " + action + " lệnh " + orderReference + " ở trạng thái " + status);
    }
}
