package com.finora.loan.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Chặn cấu hình nửa vời làm Loan ghi trạng thái gọi vốn nhưng không thể chuyển event
 * sang Investment hoặc không thể nhận kết quả đủ vốn để lập Contract.
 */
@Component
public class InvestmentIntegrationConfigurationValidator implements InitializingBean {

    private final Environment environment;

    public InvestmentIntegrationConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        requireTrue("finora.loan.outbox.publisher-enabled");
        requireValue("finora.loan.outbox.transport", "kafka");
        requireTrue("finora.loan.kafka.consumer-enabled");
    }

    private void requireTrue(String property) {
        if (!environment.getProperty(property, Boolean.class, false)) {
            throw invalid(property, "true");
        }
    }

    private void requireValue(String property, String expected) {
        String actual = environment.getProperty(property, "");
        if (!expected.equalsIgnoreCase(actual.trim())) {
            throw invalid(property, expected);
        }
    }

    private IllegalStateException invalid(String property, String expected) {
        return new IllegalStateException(
                "Investment integration yêu cầu " + property + "=" + expected);
    }
}
