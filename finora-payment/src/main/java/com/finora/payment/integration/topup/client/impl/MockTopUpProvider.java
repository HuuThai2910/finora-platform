package com.finora.payment.integration.topup.client.impl;

import com.finora.payment.integration.topup.client.TopUpProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "finora.payment.top-up", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockTopUpProvider implements TopUpProvider {
    private final Clock clock;

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public TopUpProviderResult create(TopUpProviderCommand command) {
        return new TopUpProviderResult(
                null,
                "FINORA-MOCK:" + command.topUpId(),
                clock.instant().plus(Duration.ofMinutes(15)));
    }
}
