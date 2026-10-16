package com.finora.payment.config;

import java.time.Clock;
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
     */
    @Bean
    Clock paymentClock(@Value("${finora.business-time-zone:Asia/Ho_Chi_Minh}") String businessTimeZone) {
        return businessClock(businessTimeZone);
    }

    static Clock businessClock(String businessTimeZone) {
        return Clock.system(ZoneId.of(businessTimeZone));
    }
}
