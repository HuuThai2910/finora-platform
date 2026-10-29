package com.finora.investment.client.impl;

import com.finora.investment.client.ServiceTokenProvider;
import com.finora.investment.client.UserIdentityClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Đọc họ tên và CCCD đã che từ API nội bộ của finora-user. Token service account mang client role
 * {@code user:identity:read_internal}; User chỉ trả số CCCD đã che, Investment không bao giờ thấy số đầy đủ.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "finora.investment.user", name = "mode", havingValue = "http", matchIfMissing = true)
public class RestUserIdentityClient implements UserIdentityClient {

    private final RestClient.Builder restClientBuilder;
    private final ServiceTokenProvider serviceTokenProvider;

    @Value("${finora.investment.user.base-url:http://localhost:8085}")
    private String baseUrl;

    @Override
    public UserIdentity identity(String userId) {
        try {
            IdentityBody body = restClientBuilder.baseUrl(baseUrl).build().get()
                    .uri("/api/v1/internal/users/{userId}/identity", userId)
                    .headers(headers -> headers.setBearerAuth(serviceTokenProvider.accessToken()))
                    .retrieve().body(IdentityBody.class);
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record IdentityBody(String userId, String fullName, String maskedIdNumber) {
    }
}
