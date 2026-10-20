package com.finora.loan.integration.fineract.client;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;

class FineractHttpClientTest {

    private final FineractRequestExecutor executor = new FineractRequestExecutor(null, null, new ObjectMapper());

    @Test
    void shouldMapWrappedTimeLimiterTimeoutAsRetryable() {
        FineractIntegrationException result = executor.mapException(
                new CompletionException(new TimeoutException("TimeLimiter 'fineract' recorded a timeout"))
        );

        assertThat(result.getCode()).isEqualTo("FINERACT_TIMEOUT");
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getCause()).isInstanceOf(TimeoutException.class);
    }

    @Test
    void shouldMapSocketReadTimeoutInsideResourceAccessExceptionAsTimeout() {
        FineractIntegrationException result = executor.mapException(
                new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"))
        );

        assertThat(result.getCode()).isEqualTo("FINERACT_TIMEOUT");
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getCause()).isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void shouldMapConnectionResetWhileReadingPostResponseAsRetryable() {
        // RestClient + SimpleClientHttpRequestFactory: lỗi đọc phản hồi của POST đến dưới dạng RestClientException bọc
        // UncheckedIOException, không phải ResourceAccessException (drill Q13 FineractRequestLost, B42).
        SocketException reset = new SocketException("Connection reset");
        FineractIntegrationException result = executor.mapException(new RestClientException(
                "Error while extracting response", new UncheckedIOException(reset)));

        assertThat(result.getCode()).isEqualTo("FINERACT_CONNECTION_LOST");
        assertThat(result.isRetryable()).isTrue();
        assertThat(result.getCause()).isSameAs(reset);
    }

    @Test
    void shouldMapLostPostResponseTimeoutAsTimeout() {
        FineractIntegrationException result = executor.mapException(new RestClientException(
                "Error while extracting response", new SocketTimeoutException("Read timed out")));

        assertThat(result.getCode()).isEqualTo("FINERACT_TIMEOUT");
        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    void shouldKeepMalformedFineractJsonNonRetryable() {
        FineractIntegrationException result = executor.mapException(new RestClientException(
                "Error while extracting response", new JsonParseException((JsonParser) null, "Unexpected character")));

        assertThat(result.getCode()).isEqualTo("FINERACT_UNEXPECTED_ERROR");
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void shouldExposeOnlyAllowedFineractValidationDetails() {
        HttpClientErrorException cause = httpBadRequest("""
                {
                  "defaultUserMessage": "Validation errors exist.",
                  "password": "must-not-leak",
                  "errors": [
                    {
                      "parameterName": "shortName",
                      "defaultUserMessage": "Short name must not exceed four characters.",
                      "rejectedValue": "secret-value"
                    },
                    {
                      "parameterName": "currencyCode",
                      "developerMessage": "Currency is not supported.\\nSelect an enabled currency."
                    }
                  ]
                }
                """);

        FineractIntegrationException result = executor.mapException(cause);

        assertThat(result.getCode()).isEqualTo("FINERACT_REQUEST_REJECTED");
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getMessage())
                .contains("HTTP 400")
                .contains("shortName - Short name must not exceed four characters.")
                .contains("currencyCode - Currency is not supported. Select an enabled currency.")
                .doesNotContain("must-not-leak", "secret-value", "\n");
    }

    @Test
    void shouldUseGenericMessageWhenFineractBodyIsNotJson() {
        FineractIntegrationException result = executor.mapException(httpBadRequest("<html>proxy error</html>"));

        assertThat(result.getMessage()).isEqualTo("Fineract từ chối request với HTTP 400");
    }

    @Test
    void shouldLimitNumberOfValidationErrors() {
        String detail = executor.extractValidationDetail("""
                {
                  "errors": [
                    {"parameterName":"one","defaultUserMessage":"first"},
                    {"parameterName":"two","defaultUserMessage":"second"},
                    {"parameterName":"three","defaultUserMessage":"third"},
                    {"parameterName":"four","defaultUserMessage":"must not be included"}
                  ]
                }
                """);

        assertThat(detail)
                .contains("one - first", "two - second", "three - third")
                .doesNotContain("four", "must not be included");
    }

    private HttpClientErrorException httpBadRequest(String body) {
        return HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
    }
}
