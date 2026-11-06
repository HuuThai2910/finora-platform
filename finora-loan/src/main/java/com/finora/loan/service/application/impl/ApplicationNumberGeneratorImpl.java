package com.finora.loan.service.application.impl;

import com.finora.loan.service.application.ApplicationNumberGenerator;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Sinh mã hồ sơ vay dạng {@code LA-} và 20 ký tự hex viết hoa.
 *
 * <p>Lấy 20 ký tự đầu của một UUID ngẫu nhiên đã bỏ dấu gạch.</p>
 */
@Component
public class ApplicationNumberGeneratorImpl implements ApplicationNumberGenerator {

    @Override
    public String next() {
        return "LA-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 20)
                .toUpperCase(Locale.ROOT);
    }
}
