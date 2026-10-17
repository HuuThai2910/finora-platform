package com.finora.payment.config;

import com.finora.common.environment.SimulatedSettingsCheck;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** D37: provider tiền {@code mock} chỉ hợp lệ ở local/e2e/demo; staging/production phải dùng provider thật. */
@Component
class PaymentSimulatedSettings implements SimulatedSettingsCheck {

    /** Khóa mã hóa số tài khoản mặc định trong application.yml; ai có repo đều giải mã được. */
    static final String DEV_ACCOUNT_KEY = "ZGV2LW9ubHktcGF5bWVudC1hY2NvdW50LWtleS0zMmI=";

    @Override
    public List<String> simulatedSettings(Environment environment) {
        List<String> simulated = new ArrayList<>();
        if ("mock".equalsIgnoreCase(environment.getProperty("finora.payment.disbursement.provider", "mock").trim())) {
            simulated.add("finora.payment.disbursement.provider=mock");
        }
        if ("mock".equalsIgnoreCase(environment.getProperty("finora.payment.top-up.provider", "mock").trim())) {
            simulated.add("finora.payment.top-up.provider=mock");
        }
        if ("mock".equalsIgnoreCase(environment.getProperty("finora.payment.payout.provider", "mock").trim())) {
            simulated.add("finora.payment.payout.provider=mock");
        }
        if (DEV_ACCOUNT_KEY.equals(environment.getProperty("finora.payment.withdrawal.account-encryption-key"))) {
            simulated.add("finora.payment.withdrawal.account-encryption-key (khóa dev mặc định)");
        }
        return simulated;
    }
}
