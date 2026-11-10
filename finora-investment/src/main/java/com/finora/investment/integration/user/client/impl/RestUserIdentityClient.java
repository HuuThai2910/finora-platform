package com.finora.investment.integration.user.client.impl;

import com.finora.investment.config.ServiceTokenProvider;
import com.finora.investment.integration.user.client.UserIdentityClient;
import com.finora.investment.util.CircuitBreakerCalls;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Đọc họ tên và CCCD đã che từ API nội bộ của finora-user. Token service account mang client role
 * {@code user:identity:read_internal}; User chỉ trả số CCCD đã che, Investment không bao giờ thấy số đầy đủ.
 * <p>
 * Lời gọi đi qua circuit breaker và time limiter {@value #CIRCUIT_BREAKER_NAME}; mạch mở hoặc hết giờ chờ thì ném
 * {@code UserIdentityUnavailableException} mã {@code USER_IDENTITY_UNAVAILABLE} như khi không kết nối được User.
 */
@Component
@ConditionalOnProperty(prefix = "finora.investment.user", name = "mode", havingValue = "http", matchIfMissing = true)
public class RestUserIdentityClient implements UserIdentityClient {

    /** Tên circuit breaker và time limiter trong {@code resilience4j.*.instances}. */
    static final String CIRCUIT_BREAKER_NAME = "user-identity";

    /** Giới hạn {@code userIds} của {@code GET /internal/users/full-names}. */
    static final int MAX_IDS_PER_CALL = 100;

    private final RestClient.Builder restClientBuilder;
    private final ServiceTokenProvider serviceTokenProvider;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final String baseUrl;

    public RestUserIdentityClient(
            RestClient.Builder restClientBuilder,
            ServiceTokenProvider serviceTokenProvider,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            @Value("${finora.investment.user.base-url:http://localhost:8085}") String baseUrl) {
        this.restClientBuilder = restClientBuilder;
        this.serviceTokenProvider = serviceTokenProvider;
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.baseUrl = baseUrl;
    }

    @Override
    public UserIdentity identity(String userId) {
        try {
            // Lấy token trước khi vào circuit breaker: lỗi Keycloak không tính vào mạch của User.
            String bearerToken = serviceTokenProvider.accessToken();
            IdentityBody body = CircuitBreakerCalls.run(circuitBreakerFactory, CIRCUIT_BREAKER_NAME,
                    () -> restClientBuilder.baseUrl(baseUrl).build().get()
                            .uri("/api/v1/internal/users/{userId}/identity", userId)
                            .headers(headers -> headers.setBearerAuth(bearerToken))
                            .retrieve().body(IdentityBody.class));
            if (body == null) {
                throw new UserIdentityUnavailableException("USER_IDENTITY_EMPTY", "User không trả danh tính", null);
            }
            return new UserIdentity(userId, blankToNull(body.fullName()), blankToNull(body.maskedIdNumber()));
        } catch (RestClientResponseException exception) {
            throw new UserIdentityUnavailableException("USER_IDENTITY_HTTP_" + exception.getStatusCode().value(),
                    "User từ chối đọc danh tính", exception);
        } catch (UserIdentityUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UserIdentityUnavailableException("USER_IDENTITY_UNAVAILABLE",
                    "Không kết nối được User Service", exception);
        }
    }

    @Override
    public Map<String, String> fullNames(Collection<String> userIds) {
        List<String> ids = userIds == null ? List.of()
                : userIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        Map<String, String> names = new HashMap<>();
        // User nhận tối đa 100 ID mỗi lần; trang danh sách tối đa 100 dòng nên thường chỉ một lời gọi.
        for (int from = 0; from < ids.size(); from += MAX_IDS_PER_CALL) {
            List<String> chunk = ids.subList(from, Math.min(from + MAX_IDS_PER_CALL, ids.size()));
            for (FullNameBody body : fetchFullNames(chunk)) {
                if (body != null && body.userId() != null) {
                    names.put(body.userId(), blankToNull(body.fullName()));
                }
            }
        }
        return names;
    }

    private List<FullNameBody> fetchFullNames(List<String> userIds) {
        try {
            String bearerToken = serviceTokenProvider.accessToken();
            FullNameBody[] body = CircuitBreakerCalls.run(circuitBreakerFactory, CIRCUIT_BREAKER_NAME,
                    () -> restClientBuilder.baseUrl(baseUrl).build().get()
                            .uri(uri -> uri.path("/api/v1/internal/users/full-names")
                                    .queryParam("userIds", userIds.toArray()).build())
                            .headers(headers -> headers.setBearerAuth(bearerToken))
                            .retrieve().body(FullNameBody[].class));
            return body == null ? List.of() : List.of(body);
        } catch (RestClientResponseException exception) {
            throw new UserIdentityUnavailableException("USER_IDENTITY_HTTP_" + exception.getStatusCode().value(),
                    "User từ chối đọc họ tên theo lô", exception);
        } catch (UserIdentityUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UserIdentityUnavailableException("USER_IDENTITY_UNAVAILABLE",
                    "Không kết nối được User Service", exception);
        }
    }

    @Override
    public Set<String> userIdsByName(String query) {
        try {
            String bearerToken = serviceTokenProvider.accessToken();
            IdMatchBody[] body = CircuitBreakerCalls.run(circuitBreakerFactory, CIRCUIT_BREAKER_NAME,
                    () -> restClientBuilder.baseUrl(baseUrl).build().get()
                            .uri(uri -> uri.path("/api/v1/internal/users/ids-by-name").queryParam("q", query).build())
                            .headers(headers -> headers.setBearerAuth(bearerToken))
                            .retrieve().body(IdMatchBody[].class));
            Set<String> ids = new LinkedHashSet<>();
            for (IdMatchBody match : body == null ? new IdMatchBody[0] : body) {
                if (match == null) {
                    continue;
                }
                if (match.userId() != null && !match.userId().isBlank()) {
                    ids.add(match.userId());
                }
                if (match.keycloakUserId() != null && !match.keycloakUserId().isBlank()) {
                    ids.add(match.keycloakUserId());
                }
            }
            return ids;
        } catch (RestClientResponseException exception) {
            throw new UserIdentityUnavailableException("USER_IDENTITY_HTTP_" + exception.getStatusCode().value(),
                    "User từ chối tra người dùng theo họ tên", exception);
        } catch (UserIdentityUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UserIdentityUnavailableException("USER_IDENTITY_UNAVAILABLE",
                    "Không kết nối được User Service", exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record IdentityBody(String userId, String fullName, String maskedIdNumber) {
    }

    private record FullNameBody(String userId, String fullName) {
    }

    private record IdMatchBody(String userId, String keycloakUserId) {
    }
}
