package com.finora.loan.integration.profile.client.impl;

import com.finora.common.security.service.ClientCredentialsTokenProvider;
import com.finora.loan.domain.contract.model.BorrowerIdentity;
import com.finora.loan.integration.profile.BorrowerIdentityUnavailableException;
import com.finora.loan.integration.profile.client.BorrowerIdentityClient;
import com.finora.loan.integration.profile.contract.UserIdentitySummary;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

/**
 * Đọc họ tên và CCCD đã che của người vay từ API nội bộ của finora-user bằng token service account của Loan.
 *
 * <p>Token lấy trước khi vào circuit breaker {@value #CIRCUIT_BREAKER}: lỗi Keycloak không tính vào mạch của User, và
 * lời gọi HTTP chạy trên luồng của TimeLimiter. Mọi lỗi (kể cả 404 hồ sơ không tồn tại, vì địa chỉ User cấu hình sai
 * cũng trả 404) đều ném {@link BorrowerIdentityUnavailableException} để nơi gọi thử lại; không đoán danh tính.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "finora.borrower-identity.provider", havingValue = "user", matchIfMissing = true)
public class UserServiceBorrowerIdentityClient implements BorrowerIdentityClient {

    static final String IDENTITY_PATH = "/api/v1/internal/users/{userId}/identity";
    static final String CIRCUIT_BREAKER = "user-identity";

    private final RestClient restClient;
    private final ClientCredentialsTokenProvider tokenProvider;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final Clock clock;

    public UserServiceBorrowerIdentityClient(
            @Qualifier("userIdentityRestClient") RestClient restClient,
            @Qualifier("loanServiceTokenProvider") ClientCredentialsTokenProvider tokenProvider,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            Clock clock
    ) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.clock = clock;
    }

    @Override
    public BorrowerIdentity identity(String borrowerId) {
        String bearerToken = serviceToken();
        UserIdentitySummary summary = fetch(borrowerId, bearerToken);
        if (summary == null) {
            throw new BorrowerIdentityUnavailableException(
                    "USER_IDENTITY_EMPTY", "finora-user không trả danh tính người vay", null);
        }
        BorrowerIdentity identity = new BorrowerIdentity(summary.fullName(), summary.maskedIdNumber(), Instant.now(clock));
        log.info("Đã đọc danh tính bên vay từ finora-user: borrowerId={}, hasFullName={}, hasIdNumber={}",
                borrowerId, identity.fullName() != null, identity.maskedIdNumber() != null);
        return identity;
    }

    private String serviceToken() {
        try {
            return tokenProvider.accessToken();
        } catch (RuntimeException exception) {
            log.warn("Không lấy được token service account để đọc danh tính bên vay: exceptionType={}",
                    exception.getClass().getSimpleName());
            throw new BorrowerIdentityUnavailableException(
                    "SERVICE_TOKEN_UNAVAILABLE", "Không lấy được token service account của Loan", exception);
        }
    }

    private UserIdentitySummary fetch(String borrowerId, String bearerToken) {
        return circuitBreakerFactory.create(CIRCUIT_BREAKER).run(
                () -> restClient.get()
                        .uri(IDENTITY_PATH, borrowerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(UserIdentitySummary.class),
                throwable -> {
                    throw mapException(throwable);
                });
    }

    /** Phân loại lỗi để log/đối soát; mọi nhánh đều là "chưa đọc được", nơi gọi xử lý như nhau. */
    RuntimeException mapException(Throwable throwable) {
        if (throwable instanceof RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == HttpStatus.UNAUTHORIZED.value()) {
                // Keycloak có thể đã đổi khóa ký hoặc token bị thu hồi: lượt thử sau xin token mới.
                tokenProvider.invalidate();
            }
            log.warn("finora-user từ chối đọc danh tính bên vay: status={}", status);
            return new BorrowerIdentityUnavailableException(
                    "USER_IDENTITY_HTTP_" + status, "finora-user từ chối đọc danh tính bên vay", exception);
        }
        if (throwable instanceof InterruptedException) {
            // Luồng đang chờ TimeLimiter bị ngắt: trả lại cờ interrupt cho nơi gọi.
            Thread.currentThread().interrupt();
        }
        if (throwable instanceof ResourceAccessException
                || throwable instanceof CallNotPermittedException
                || throwable instanceof TimeoutException
                || throwable instanceof InterruptedException) {
            log.warn("Không kết nối được finora-user khi đọc danh tính bên vay: exceptionType={}",
                    throwable.getClass().getSimpleName());
            return new BorrowerIdentityUnavailableException(
                    "USER_IDENTITY_UNREACHABLE", "Không kết nối được finora-user", throwable);
        }
        if (throwable instanceof RestClientException exception) {
            return new BorrowerIdentityUnavailableException(
                    "USER_IDENTITY_RESPONSE_INVALID", "Không đọc được danh tính bên vay từ finora-user", exception);
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new BorrowerIdentityUnavailableException(
                "USER_IDENTITY_UNAVAILABLE", "Không đọc được danh tính bên vay từ finora-user", throwable);
    }
}
