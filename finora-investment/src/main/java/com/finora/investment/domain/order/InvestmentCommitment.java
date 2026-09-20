package com.finora.investment.domain.order;

import com.finora.common.enums.investment.CommitmentStatus;
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
 * Entity lưu thông tin phần vốn cam kết của nhà đầu tư.
 */
@Entity
@Table(name = "investment_commitments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestmentCommitment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true, updatable = false)
    private Long orderId;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Column(name = "investor_id", nullable = false, length = 100, updatable = false)
    private String investorId;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "note_count", nullable = false, updatable = false)
    private Integer noteCount;

    @Column(name = "note_denomination", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal noteDenomination;

    @Column(name = "share_percent", nullable = false, precision = 9, scale = 6, updatable = false)
    private BigDecimal sharePercent;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private CommitmentStatus status;

    @Column(name = "payment_hold_reference", nullable = false, length = 100, updatable = false)
    private String paymentHoldReference;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

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
