package com.finora.loan.service.pricing;

import com.finora.loan.config.RiskBasedPricingProperties;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.service.pricing.impl.RiskBasedPricingServiceImpl;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskBasedPricingServiceTest {

    private final RiskBasedPricingService service = new RiskBasedPricingServiceImpl(
            new RiskBasedPricingProperties(
                    "RISK_PRICING_TEST_V1",
                    new BigDecimal("20.0000"),
                    Map.of(
                            "A", new BigDecimal("-0.5000"),
                            "B", BigDecimal.ZERO,
                            "D", new BigDecimal("1.0000")
                    )
            )
    );

    @Test
    void gradeADecreasesBaseRateWithinProductRange() {
        var result = service.calculate(application("10", "12.5", "15"), "A");

        assertThat(result.finalAnnualInterestRate()).isEqualByComparingTo("12.0000");
        assertThat(result.appliedAdjustmentPercentagePoints()).isEqualByComparingTo("-0.5000");
    }

    @Test
    void finalRateIsClampedAtProductMaximum() {
        var result = service.calculate(application("10", "14.5", "15"), "D");

        assertThat(result.finalAnnualInterestRate()).isEqualByComparingTo("15.0000");
        assertThat(result.appliedAdjustmentPercentagePoints()).isEqualByComparingTo("0.5000");
    }

    @Test
    void rejectsProductWhoseBaseRateExceedsTheCurrentComplianceCap() {
        RiskBasedPricingService lowerCapService = new RiskBasedPricingServiceImpl(
                new RiskBasedPricingProperties(
                        "RISK_PRICING_LOWER_CAP_V1",
                        new BigDecimal("10.0000"),
                        Map.of("B", BigDecimal.ZERO)
                )
        );

        assertThatThrownBy(() -> lowerCapService.calculate(application("8", "12", "15"), "B"))
                .hasMessageContaining("vượt trần tuân thủ hiện tại");
    }

    private LoanApplication application(String min, String base, String max) {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getMinAnnualInterestRateSnapshot()).thenReturn(new BigDecimal(min));
        when(application.getAnnualInterestRateSnapshot()).thenReturn(new BigDecimal(base));
        when(application.getMaxAnnualInterestRateSnapshot()).thenReturn(new BigDecimal(max));
        return application;
    }
}
