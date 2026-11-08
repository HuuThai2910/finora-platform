package com.finora.payment.integration.user.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finora.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Đọc hồ sơ của chính người dùng ở {@code GET /api/v1/users/me} bằng <b>token của người dùng</b>, như Loan đọc eKYC người
 * vay (D4): người dùng tự cho phép đọc dữ liệu của mình, Payment không cần service account hay quyền đọc hồ sơ người khác.
 * Chỉ giữ họ tên và trạng thái eKYC; các trường khác của hồ sơ bị bỏ qua ngay khi đọc.
 */
@Slf4j
@Component
public class RestUserIdentityClient implements UserIdentityClient {

    static final String PROFILE_PATH = "/api/v1/users/me";

    private final RestClient restClient;

    public RestUserIdentityClient(@Qualifier("paymentUserRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public CurrentUser currentUser() {
        ProfileView profile;
        try {
            profile = restClient.get()
                    .uri(PROFILE_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentBearerToken())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(ProfileView.class);
        } catch (RestClientResponseException exception) {
            log.warn("finora-user từ chối đọc hồ sơ: status={}", exception.getStatusCode().value());
            throw unavailable();
        } catch (ResourceAccessException exception) {
            log.warn("Không kết nối được finora-user: exceptionType={}", exception.getClass().getSimpleName());
            throw unavailable();
        } catch (RestClientException exception) {
            log.warn("Không đọc được hồ sơ từ finora-user: exceptionType={}", exception.getClass().getSimpleName());
            throw unavailable();
        }
        if (profile == null || profile.id() == null || profile.ekycStatus() == null) {
            throw unavailable();
        }
        return new CurrentUser(String.valueOf(profile.id()), profile.fullName(), profile.ekycStatus());
    }

    private static BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "USER_PROFILE_UNAVAILABLE",
                "Chưa đọc được thông tin định danh của bạn. Vui lòng thử lại sau.");
    }

    private static String currentBearerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Cần đăng nhập để liên kết ngân hàng");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProfileView(Long id, String fullName, String ekycStatus) {
    }
}
