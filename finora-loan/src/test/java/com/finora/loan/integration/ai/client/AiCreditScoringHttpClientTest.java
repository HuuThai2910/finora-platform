package com.finora.loan.integration.ai.client;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import java.net.SocketException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

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

    @Test
    void shouldMapConnectionResetWhileReadingScoreAsRetryable() {
        // RestClient bọc lỗi đọc phản hồi của POST thành RestClientException, không phải ResourceAccessException (B42).
        AiCreditIntegrationException result = client.mapException(new CompletionException(
                new RestClientException("Error while extracting response", new SocketException("Connection reset"))));

        assertThat(result.getCode()).isEqualTo("AI_UNAVAILABLE");
        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    void shouldRetryWhenAiRejectsAnExpiredServiceToken() {
        // Interceptor của token service account đã bỏ token cũ khi nhận 401; lần thử lại lấy token mới (D34).
        AiCreditIntegrationException result = client.mapException(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null));

        assertThat(result.getCode()).isEqualTo("AI_TEMPORARY_ERROR");
        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    void shouldNotRetryWhenTheServiceAccountLacksTheScoringRole() {
        AiCreditIntegrationException result = client.mapException(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null));

        assertThat(result.getCode()).isEqualTo("AI_REQUEST_REJECTED");
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void shouldKeepMalformedScoreJsonNonRetryable() {
        AiCreditIntegrationException result = client.mapException(new RestClientException(
                "Error while extracting response", new JsonParseException((JsonParser) null, "Unexpected character")));

        assertThat(result.getCode()).isEqualTo("AI_UNEXPECTED_ERROR");
        assertThat(result.isRetryable()).isFalse();
    }

    private void assertTimeoutIsRetryable(AiCreditIntegrationException result) {
        assertThat(result.getCode()).isEqualTo("AI_TIMEOUT");
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getCause()).isInstanceOf(TimeoutException.class);
    }
}
