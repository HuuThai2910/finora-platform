package com.finora.common.environment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Cổng môi trường lúc khởi động (D37). Mọi service quét {@code com.finora.common} nên cùng chạy kiểm tra chung (khóa PIN
 * dev, đồng hồ/điểm dừng E2E) cộng với {@link SimulatedSettingsCheck} riêng của service. Ở {@code staging}/
 * {@code production} còn cấu hình giả lập thì dừng khởi động và nêu tên khóa; ở {@code local}/{@code e2e}/{@code demo}
 * chỉ log cảnh báo.
 */
@Slf4j
@Configuration
public class FinoraEnvironmentConfig {

    /** Khóa ký token PIN mặc định trong {@code application.yml}; ai có repo đều ký được token với khóa này. */
    static final String DEV_PIN_TOKEN_SECRET = "dev-only-pin-token-secret-change-me-32b";

    @Bean
    FinoraEnvironment finoraEnvironment(Environment environment, ObjectProvider<SimulatedSettingsCheck> checks) {
        FinoraEnvironment current = FinoraEnvironment.parse(environment.getProperty(FinoraEnvironment.PROPERTY));
        List<String> simulated = new ArrayList<>(commonSimulatedSettings(environment));
        checks.orderedStream().forEach(check -> simulated.addAll(check.simulatedSettings(environment)));
        if (!simulated.isEmpty()) {
            if (!current.allowsSimulation()) {
                throw new IllegalStateException("FINORA_ENVIRONMENT=" + current.key()
                        + " không được chạy với cấu hình giả lập/dev: " + String.join(", ", simulated));
            }
            log.warn("Chạy với cấu hình giả lập/dev, chỉ hợp lệ ở local/e2e/demo: environment={}, settings={}",
                    current.key(), simulated);
        }
        return current;
    }

    static List<String> commonSimulatedSettings(Environment environment) {
        List<String> simulated = new ArrayList<>();
        if (DEV_PIN_TOKEN_SECRET.equals(environment.getProperty("finora.pin.token-secret"))) {
            simulated.add("finora.pin.token-secret (khóa dev mặc định)");
        }
        String clockOffset = environment.getProperty("finora.e2e.clock-offset");
        if (clockOffset != null && !clockOffset.isBlank() && !Duration.parse(clockOffset.trim()).isZero()) {
            simulated.add("finora.e2e.clock-offset");
        }
        String pausePoint = environment.getProperty("finora.e2e.pause-point");
        if (pausePoint != null && !pausePoint.isBlank()) {
            simulated.add("finora.e2e.pause-point");
        }
        return simulated;
    }
}
