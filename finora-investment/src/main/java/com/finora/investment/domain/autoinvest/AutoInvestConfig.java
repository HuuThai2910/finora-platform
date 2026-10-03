package com.finora.investment.domain.autoinvest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bộ tiêu chí Auto-Invest của một nhà đầu tư.
 *
 * <p>Mỗi nhà đầu tư một dòng. {@code enabledAt} là vị trí trong hàng chờ: khi nhiều người cùng
 * khớp một khoản vay, ai bật trước được khớp trước. Tắt rồi bật lại thì xuống cuối hàng.</p>
 */
@Entity
@Table(name = "auto_invest_configs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AutoInvestConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "investor_id", nullable = false, length = 100, updatable = false)
    private String investorId;

    @Column(nullable = false)
    private boolean enabled;

    /** CSV tên hạng — bảng hạng là cấu hình động bên finora-ai nên không dùng enum. */
    @Column(nullable = false, length = 100)
    private String grades;

    @Column(name = "min_annual_rate", nullable = false, precision = 7, scale = 4)
    private BigDecimal minAnnualRate;

    @Column(name = "max_term_months", nullable = false)
    private Integer maxTermMonths;

    @Column(name = "amount_per_loan", nullable = false, precision = 18, scale = 2)
    private BigDecimal amountPerLoan;

    @Column(name = "enabled_at")
    private Instant enabledAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static AutoInvestConfig create(String investorId, Instant now) {
        AutoInvestConfig config = new AutoInvestConfig();
        config.investorId = investorId;
        config.enabled = false;
        config.createdAt = now;
        return config;
    }

    /**
     * Ghi đè toàn bộ tiêu chí. Chỉ lần chuyển tắt → bật mới đặt lại {@code enabledAt}; lưu lại
     * tiêu chí khi đang bật không làm mất chỗ trong hàng chờ.
     */
    public void update(boolean enabled, List<String> grades, BigDecimal minAnnualRate,
                       int maxTermMonths, BigDecimal amountPerLoan, Instant now) {
        if (enabled && !this.enabled) {
            this.enabledAt = now;
        }
        this.enabled = enabled;
        this.grades = String.join(",", new LinkedHashSet<>(grades));
        this.minAnnualRate = minAnnualRate;
        this.maxTermMonths = maxTermMonths;
        this.amountPerLoan = amountPerLoan.setScale(2, RoundingMode.DOWN);
        this.updatedAt = now;
    }

    public Set<String> gradeSet() {
        return new LinkedHashSet<>(Arrays.asList(grades.split(",")));
    }

    /**
     * Khoản vay có khớp tiêu chí không.
     *
     * <p>Chỉ xét khoản mở gọi vốn từ lúc bật trở đi: bật Auto-Invest không được quét ngược và
     * đổ tiền vào mọi khoản đang mở.</p>
     */
    public boolean accepts(String creditGrade, BigDecimal annualInterestRate, int termMonths,
                           Instant fundingOpenedAt) {
        return enabled
                && enabledAt != null
                && !fundingOpenedAt.isBefore(enabledAt)
                && creditGrade != null
                && gradeSet().contains(creditGrade)
                && annualInterestRate.compareTo(minAnnualRate) >= 0
                && termMonths <= maxTermMonths;
    }

    /**
     * Số tiền đặt cho một khoản: không vượt {@code amountPerLoan} và vốn còn lại, làm tròn
     * xuống bội số mệnh giá Note. Trả về 0 khi không đủ một Note.
     */
    public static BigDecimal orderAmount(BigDecimal amountPerLoan, BigDecimal remaining,
                                         BigDecimal noteDenomination) {
        BigDecimal cap = amountPerLoan.min(remaining);
        if (cap.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal notes = cap.divide(noteDenomination, 0, RoundingMode.DOWN);
        return notes.multiply(noteDenomination).setScale(2, RoundingMode.UNNECESSARY);
    }
}
