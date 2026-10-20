package com.finora.user.config;

import com.finora.common.environment.SimulatedSettingsCheck;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** D37: khóa HMAC/AES mặc định trong {@code application.yml} (mã hóa CCCD, số điện thoại) chỉ hợp lệ ở local/e2e/demo. */
@Component
class UserSimulatedSettings implements SimulatedSettingsCheck {

    @Override
    public List<String> simulatedSettings(Environment environment) {
        List<String> simulated = new ArrayList<>();
        if (environment.getProperty("finora.crypto.hmac-secret", "change-me").trim().startsWith("change-me")) {
            simulated.add("finora.crypto.hmac-secret (khóa mặc định)");
        }
        if (environment.getProperty("finora.crypto.aes-secret", "change-me").trim().startsWith("change-me")) {
            simulated.add("finora.crypto.aes-secret (khóa mặc định)");
        }
        return simulated;
    }
}
