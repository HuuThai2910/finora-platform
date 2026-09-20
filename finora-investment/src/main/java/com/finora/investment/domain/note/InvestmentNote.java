package com.finora.investment.domain.note;

import com.finora.common.enums.investment.NoteStatus;
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
 * Entity lưu thông tin chứng chỉ đầu tư (Note).
 */
@Entity
@Table(name = "investment_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestmentNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_number", nullable = false, length = 50, unique = true, updatable = false)
    private String noteNumber;

    @Column(name = "commitment_id", nullable = false, updatable = false)
    private Long commitmentId;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private Long loanId;

    @Column(name = "investor_id", nullable = false, length = 100)
    private String investorId;

    @Column(name = "principal_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal principalAmount;

    @Column(name = "outstanding_principal", nullable = false, precision = 18, scale = 2)
    private BigDecimal outstandingPrincipal;

    @Column(name = "principal_repaid", nullable = false, precision = 18, scale = 2)
    private BigDecimal principalRepaid;

    @Column(name = "interest_received", nullable = false, precision = 18, scale = 2)
    private BigDecimal interestReceived;

    @Column(name = "annual_interest_rate", nullable = false, precision = 7, scale = 4, updatable = false)
    private BigDecimal annualInterestRate;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "term_months", nullable = false, updatable = false)
    private Integer termMonths;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private Integer sequenceNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private NoteStatus status;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

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
