package com.finora.loan.service.application;

import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ApplicationNumberGenerator {

    public String next() {
        return "LA-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 20)
                .toUpperCase(Locale.ROOT);
    }
}
