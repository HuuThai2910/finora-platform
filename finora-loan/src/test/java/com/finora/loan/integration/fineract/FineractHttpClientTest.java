package com.finora.loan.integration.fineract;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class FineractHttpClientTest {

    private final FineractHttpClient client = new FineractHttpClient(null, null, null, null);

    @Test
    void shouldMapWrappedTimeLimiterTimeoutAsRetryable() {
        FineractIntegrationException result = client.mapException(
                new CompletionException(new TimeoutException("TimeLimiter 'fineract' recorded a timeout"))
        );

        assertThat(result.getCode()).isEqualTo("FINERACT_TIMEOUT");
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getCause()).isInstanceOf(TimeoutException.class);
    }
}
