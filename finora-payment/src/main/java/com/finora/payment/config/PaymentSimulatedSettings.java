package com.finora.payment.config;

import com.finora.common.environment.SimulatedSettingsCheck;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** D37: provider tiền {@code mock} chỉ hợp lệ ở local/e2e/demo; staging/production phải dùng provider thật. */
@Component
class PaymentSimulatedSettings implements SimulatedSettingsCheck {

    @Override
    public List<String> simulatedSettings(Environment environment) {
        List<String> simulated = new ArrayList<>();
        if ("mock".equalsIgnoreCase(environment.getProperty("finora.payment.disbursement.provider", "mock").trim())) {
            simulated.add("finora.payment.disbursement.provider=mock");
        }
        if ("mock".equalsIgnoreCase(environment.getProperty("finora.payment.top-up.provider", "mock").trim())) {
            simulated.add("finora.payment.top-up.provider=mock");
        }
        return simulated;
    }
}
