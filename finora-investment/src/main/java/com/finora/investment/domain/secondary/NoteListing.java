package com.finora.investment.domain.secondary;

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
 * Tin đăng bán một Note trên chợ thứ cấp.
 *
 * <p>Treo bán <strong>không</strong> chuyển quyền sở hữu: Note vẫn thuộc người bán và vẫn nhận
 * gốc lãi bình thường cho tới khi có người mua. Vì vậy trạng thái đăng bán nằm ở đây chứ không
 * phải trên {@code NoteStatus} — nếu đổi trạng thái Note thì Note sẽ rơi khỏi danh mục của
 * người bán, vốn chỉ lấy Note {@code ACTIVE}.</p>
 */
@Entity
@Table(name = "note_listings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoteListing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_reference", nullable = false, length = 50, unique = true, updatable = false)
    private String listingReference;

    @Column(name = "note_id", nullable = false, updatable = false)
    private Long noteId;

    @Column(name = "seller_id", nullable = false, length = 100, updatable = false)
    private String sellerId;

    /** Giá người bán đặt; trần là dư nợ gốc còn lại của Note. */
    @Column(name = "asking_price", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal askingPrice;

    /**
     * Dư nợ gốc lúc đăng bán.
     *
     * <p>Chụp lại để đối soát, không dùng để kiểm trần lúc mua: người vay có thể trả nợ trong
     * lúc tin đang treo, nên trần phải đọc lại dư nợ hiện tại của Note.</p>
     */
    @Column(name = "outstanding_at_listing", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal outstandingAtListing;

    /** Note đang nợ xấu lúc đăng bán; giao diện phải cảnh báo ở cả hai phía. */
    @Column(name = "defaulted_at_listing", nullable = false, updatable = false)
    private Boolean defaultedAtListing;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private NoteListingStatus status;

    @Column(name = "buyer_id", length = 100)
    private String buyerId;

    @Column(name = "sold_at")
    private Instant soldAt;

    /**
     * Số tiền thực tế của giao dịch, chụp tại thời điểm khớp.
     *
     * <p>Không tính lại từ {@code askingPrice} khi đọc: mức phí là policy có thể đổi, còn bản
     * ghi lịch sử thì không được đổi theo.</p>
     */
    @Column(name = "sold_price", precision = 18, scale = 2)
    private BigDecimal soldPrice;

    @Column(name = "platform_fee", precision = 18, scale = 2)
    private BigDecimal platformFee;

    @Column(name = "seller_proceeds", precision = 18, scale = 2)
    private BigDecimal sellerProceeds;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

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
}
