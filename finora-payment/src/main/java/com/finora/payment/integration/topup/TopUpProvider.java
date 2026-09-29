package com.finora.payment.integration.topup;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface TopUpProvider {
    String name();

    TopUpProviderResult create(TopUpProviderCommand command);

    record TopUpProviderCommand(
            UUID topUpId,
            String providerOrderId,
            String ownerId,
            BigDecimal amount,
            String currency
    ) {}

    record TopUpProviderResult(String checkoutUrl, String qrPayload, Instant expiresAt) {}
}
