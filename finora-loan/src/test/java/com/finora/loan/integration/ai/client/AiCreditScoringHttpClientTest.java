package com.finora.loan.integration.ai.client;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiCreditScoringHttpClientTest {

    private final AiCreditScoringHttpClient client = new AiCreditScoringHttpClient(null, null, null);

    @Test
    void shouldMapDirectTimeLimiterTimeoutAsRetryable() {
        AiCreditIntegrationException result = client.mapException(
                new TimeoutException("TimeLimiter 'ai-credit' recorded a timeout")
        );

        assertTimeoutIsRetryable(result);
    }

    @Test
    void shouldMapWrappedTimeLimiterTimeoutAsRetryable() {
        AiCreditIntegrationException result = client.mapException(
                new CompletionException(new TimeoutException("TimeLimiter 'ai-credit' recorded a timeout"))
        );

        assertTimeoutIsRetryable(result);
    }

    private void assertTimeoutIsRetryable(AiCreditIntegrationException result) {
        assertThat(result.getCode()).isEqualTo("AI_TIMEOUT");
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getCause()).isInstanceOf(TimeoutException.class);
    }
}
