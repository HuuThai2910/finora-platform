package com.finora.loan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.ZoneId;

@Configuration
@EnableConfigurationProperties({
        LoanPricingDisclosureProperties.class,
        LoanContractProperties.class,
        RiskBasedPricingProperties.class,
        SignatureProviderProperties.class,
        InvestmentIntegrationProperties.class,
        OutboxProperties.class,
        LoanRescheduleProperties.class,
        DisbursementSagaProperties.class,
        BorrowerExposureProperties.class
})
public class LoanTimeConfig {

    /**
     * Clock theo múi giờ nghiệp vụ (D7: {@code Asia/Ho_Chi_Minh}). {@code Instant} không đổi theo zone; chỉ
     * {@code LocalDate.now(clock)} — ngày nộp hồ sơ, booking, servicing — trả ngày Việt Nam thay vì ngày UTC, vốn lùi
     * một ngày từ 00:00 đến 07:00 và làm Fineract ghi giải ngân sai ngày. Zone không hợp lệ làm service dừng khi khởi động.
     */
    @Bean
    Clock loanClock(@Value("${finora.business-time-zone:Asia/Ho_Chi_Minh}") String businessTimeZone) {
        return businessClock(businessTimeZone);
    }

    static Clock businessClock(String businessTimeZone) {
        return Clock.system(ZoneId.of(businessTimeZone));
    }
}
