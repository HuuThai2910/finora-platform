package com.finora.blockchain.config;

import com.finora.common.environment.SimulatedSettingsCheck;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** D37: receipt proof MOCK chỉ hợp lệ ở local/e2e/demo; production phải ghi Fabric thật (R6). */
@Component
class BlockchainSimulatedSettings implements SimulatedSettingsCheck {

    @Override
    public List<String> simulatedSettings(Environment environment) {
        List<String> simulated = new ArrayList<>();
        if ("MOCK".equalsIgnoreCase(environment.getProperty("finora.blockchain.proof.provider", "MOCK").trim())) {
            simulated.add("finora.blockchain.proof.provider=MOCK");
        }
        return simulated;
    }
}
