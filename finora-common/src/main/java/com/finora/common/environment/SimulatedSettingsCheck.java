package com.finora.common.environment;

import java.util.List;
import org.springframework.core.env.Environment;

/**
 * Service khai báo cấu hình giả lập/dev của riêng nó (D37), ví dụ Payment có provider tiền {@code mock}. Trả tên khóa
 * cấu hình kèm giá trị giả lập không bí mật (như {@code =mock}); không bao giờ trả giá trị của secret.
 */
@FunctionalInterface
public interface SimulatedSettingsCheck {

    List<String> simulatedSettings(Environment environment);
}
