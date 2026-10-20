package com.finora.payment.config;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentTimeConfiguration {

    /**
     * Clock theo múi giờ nghiệp vụ (D7: {@code Asia/Ho_Chi_Minh}). {@code Instant} không đổi; ngày trả nợ, ngày tính
     * báo giá và mã đơn ZaloPay ({@code yyMMdd} theo GMT+7) lấy theo ngày Việt Nam. Trước đây ngày UTC làm giao dịch
     * từ 00:00 đến 07:00 bị Fineract ghi vào ngày hôm trước.
     *
     * <p>{@code finora.e2e.clock-offset} (mặc định 0) dời đồng hồ để harness E2E kiểm khoản trả kỳ được giữ tới ngày đến
     * hạn (D16, Q12) mà không phải chờ cả tháng. Chỉ nhận khi provider giải ngân và nạp tiền đều là {@code mock}: không
     * bao giờ chạy tiền thật trên một đồng hồ giả.</p>
     */
    @Bean
    Clock paymentClock(@Value("${finora.business-time-zone:Asia/Ho_Chi_Minh}") String businessTimeZone,
            @Value("${finora.e2e.clock-offset:PT0S}") Duration e2eOffset,
            @Value("${finora.payment.disbursement.provider:mock}") String disbursementProvider,
            @Value("${finora.payment.top-up.provider:mock}") String topUpProvider) {
        return e2eClock(businessClock(businessTimeZone), e2eOffset, disbursementProvider, topUpProvider);
    }

    static Clock e2eClock(Clock business, Duration offset, String disbursementProvider, String topUpProvider) {
        if (offset == null || offset.isZero()) {
            return business;
        }
        if (!"mock".equalsIgnoreCase(disbursementProvider) || !"mock".equalsIgnoreCase(topUpProvider)) {
            throw new IllegalStateException(
                    "finora.e2e.clock-offset chỉ dùng khi provider giải ngân và nạp tiền đều là mock");
        }
        return Clock.offset(business, offset);
    }

    static Clock businessClock(String businessTimeZone) {
        return Clock.system(ZoneId.of(businessTimeZone));
    }
}
