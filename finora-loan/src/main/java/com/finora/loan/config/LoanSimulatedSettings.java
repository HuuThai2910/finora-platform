package com.finora.loan.config;

import com.finora.common.environment.SimulatedSettingsCheck;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** D37: chữ ký MOCK, booking Fineract mock và hồ sơ người vay mock chỉ hợp lệ ở local/e2e/demo. */
@Component
class LoanSimulatedSettings implements SimulatedSettingsCheck {

    @Override
    public List<String> simulatedSettings(Environment environment) {
        List<String> simulated = new ArrayList<>();
        if ("MOCK".equalsIgnoreCase(environment.getProperty("finora.signature.provider", "MOCK").trim())) {
            simulated.add("finora.signature.provider=MOCK");
        }
        if ("mock".equalsIgnoreCase(environment.getProperty("finora.fineract.booking-provider", "mock").trim())) {
            simulated.add("finora.fineract.booking-provider=mock");
        }
        if ("mock".equalsIgnoreCase(environment.getProperty("finora.borrower-profile.provider", "user").trim())) {
            simulated.add("finora.borrower-profile.provider=mock");
        }
        return simulated;
    }
}
