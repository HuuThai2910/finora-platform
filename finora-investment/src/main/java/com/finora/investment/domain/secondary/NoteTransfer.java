package com.finora.investment.domain.secondary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Một lần chuyển nhượng Note đã hoàn tất.
 *
 * <p>Bản ghi <strong>bất biến</strong>: không có setter, không có cột version, mọi cột đều
 * {@code updatable = false}. Tin đăng bán còn đổi trạng thái được, còn giao dịch đã xảy ra thì
 * không — nếu cần sửa thì ghi một bản ghi đối ứng, không sửa bản ghi cũ.</p>
 *
 * <p>Dùng chung cho hai nguồn: bảng tin cũ (V2, đã thay bằng sổ lệnh) và sổ lệnh (V9). Tách khỏi
 * nguồn vì một Note có thể được chuyển nhượng nhiều lần: người mua hôm nay có thể là người bán
 * tháng sau.</p>
 */
@Entity
@Table(name = "note_transfers")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NoteTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false, updatable = false)
    private Long noteId;

    /** Tin đăng bán của bảng tin cũ (V2); null với chuyển nhượng qua sổ lệnh. */
    @Column(name = "note_listing_id", updatable = false)
    private Long noteListingId;

    /**
     * Lần khớp trên sổ lệnh đã chuyển Note này; null với chuyển nhượng qua bảng tin cũ. Một lần
     * khớp gồm nhiều Note nên nhiều dòng chung một {@code tradeId}.
     */
    @Column(name = "trade_id", updatable = false)
    private Long tradeId;

    @Column(name = "seller_id", nullable = false, length = 100, updatable = false)
    private String sellerId;

    @Column(name = "buyer_id", nullable = false, length = 100, updatable = false)
    private String buyerId;

    /** Giá người mua trả. */
    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal price;

    @Column(name = "platform_fee", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal platformFee;

    /** Tiền người bán nhận; luôn bằng {@code price} trừ {@code platformFee}. */
    @Column(name = "seller_proceeds", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal sellerProceeds;

    /**
     * Dư nợ gốc tại thời điểm chuyển nhượng.
     *
     * <p>Cần để đối soát về sau xem giá có nằm trong trần hay không, mà không phải suy lại từ
     * dòng tiền đã chạy tiếp sau đó.</p>
     */
    @Column(name = "outstanding_at_transfer", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal outstandingAtTransfer;

    /** Note đang nợ xấu lúc chuyển nhượng; lưu để đối soát việc đã cảnh báo người mua. */
    @Column(name = "defaulted_at_transfer", nullable = false, updatable = false)
    private Boolean defaultedAtTransfer;

    /** Mã chống trùng phía Payment; với sổ lệnh là {@code tradeReference}, chung cho cả lần khớp. */
    @Column(name = "payment_reference", nullable = false, length = 100, updatable = false)
    private String paymentReference;

    @Column(name = "transferred_at", nullable = false, updatable = false)
    private Instant transferredAt;

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
