package com.finora.investment.domain.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entity lưu tham số gọi vốn toàn sàn.
 */
@Entity
@Table(name = "funding_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundingSettings {

    public static final short SINGLETON_ID = 1;

    @Id
    private Short id;

    @Column(name = "note_denomination", nullable = false)
    private BigDecimal noteDenomination;

    @Column(name = "min_investment_amount", nullable = false)
    private BigDecimal minInvestmentAmount;

    @Column(name = "funding_days", nullable = false)
    private Short fundingDays;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;
}
