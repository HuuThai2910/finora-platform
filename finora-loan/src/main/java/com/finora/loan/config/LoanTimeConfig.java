package com.finora.loan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
import java.time.Duration;
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
     *
     * <p>{@code finora.e2e.clock-offset} (mặc định 0) dời đồng hồ để harness E2E kiểm quá hạn, nợ xấu và khắc phục
     * (Q12, Wave 2D). Chỉ nhận khi chữ ký là {@code MOCK} và profile {@code local} đang bật: chữ ký số thật không được mang
     * thời điểm giả và môi trường demo không thể vô tình chạy đồng hồ lệch.</p>
     */
    @Bean
    Clock loanClock(@Value("${finora.business-time-zone:Asia/Ho_Chi_Minh}") String businessTimeZone,
            @Value("${finora.e2e.clock-offset:PT0S}") String e2eOffset,
            @Value("${finora.signature.provider:MOCK}") String signatureProvider,
            Environment environment) {
        // Đọc chuỗi rồi tự parse: context không có ConversionService của Spring Boot vẫn khởi tạo được bean này.
        Duration offset = e2eOffset == null || e2eOffset.isBlank() ? Duration.ZERO : Duration.parse(e2eOffset.trim());
        return e2eClock(businessClock(businessTimeZone), offset, signatureProvider,
                environment.acceptsProfiles(Profiles.of("local")));
    }

    static Clock e2eClock(Clock business, Duration offset, String signatureProvider, boolean localProfile) {
        if (offset == null || offset.isZero()) {
            return business;
        }
        if (!"MOCK".equalsIgnoreCase(signatureProvider) || !localProfile) {
            throw new IllegalStateException(
                    "finora.e2e.clock-offset chỉ dùng với chữ ký MOCK và profile local");
        }
        return Clock.offset(business, offset);
    }

    static Clock businessClock(String businessTimeZone) {
        return Clock.system(ZoneId.of(businessTimeZone));
    }
}
