package com.finora.investment.config;

import com.finora.common.environment.SimulatedSettingsCheck;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** D37: tắt cổng eKYC nhà đầu tư (stack E2E, D11) chỉ hợp lệ ở local/e2e/demo. */
@Component
class InvestmentSimulatedSettings implements SimulatedSettingsCheck {

    @Override
    public List<String> simulatedSettings(Environment environment) {
        List<String> simulated = new ArrayList<>();
        if ("false".equalsIgnoreCase(environment.getProperty("finora.investment.require-ekyc", "true").trim())) {
            simulated.add("finora.investment.require-ekyc=false");
        }
        return simulated;
    }
}
