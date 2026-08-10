package com.finora.loan.service.contract;

import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ContractNumberGenerator {

    public String next() {
        return "LC-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 20)
                .toUpperCase(Locale.ROOT);
    }
}
