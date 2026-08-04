package com.finora.loan.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

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
