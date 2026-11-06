package com.finora.loan.integration.profile.client.impl;

import com.finora.loan.domain.scoring.enums.BorrowerKycStatus;
import com.finora.loan.domain.scoring.enums.BorrowerProfileSource;
import com.finora.loan.domain.scoring.enums.IncomeVerificationStatus;
import com.finora.loan.integration.profile.BorrowerProfileUnavailableException;
import com.finora.loan.integration.profile.client.BorrowerProfileProvider;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import com.finora.loan.integration.profile.contract.UserKycSummary;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.TimeoutException;

/**
 * Đọc trạng thái eKYC và tuổi người vay từ finora-user bằng chính token của người vay (W2-KYC, quyết định D4).
 *
 * <p>Chuyển tiếp token thay vì dùng service account: người vay tự cho phép đọc dữ liệu của mình, Loan không cần quyền đọc
 * hồ sơ người khác và không có secret dùng chung mới. Vì cần token nên chỉ gọi được trong request nộp hồ sơ.</p>
 *
 * <p>Cuộc gọi đi qua circuit breaker {@value #CIRCUIT_BREAKER} như các client gọi ra ngoài khác của Loan. Token
 * được đọc trước khi vào circuit breaker vì lời gọi HTTP chạy trên luồng của TimeLimiter, không có SecurityContext
 * của request.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "finora.borrower-profile.provider", havingValue = "user")
public class UserServiceBorrowerProfileProvider implements BorrowerProfileProvider {

    static final String KYC_SUMMARY_PATH = "/api/v1/users/me/kyc-summary";
    static final String KYC_VERSION = "USER_KYC_SUMMARY_V1";
    static final String CIRCUIT_BREAKER = "user-profile";

    private final RestClient restClient;
    private final Clock clock;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public UserServiceBorrowerProfileProvider(
            @Qualifier("userRestClient") RestClient restClient,
            Clock clock,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory
    ) {
        this.restClient = restClient;
        this.clock = clock;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    @Override
    public BorrowerProfileResult getBorrowerProfile(String borrowerId, LocalDate asOf) {
        UserKycSummary summary = fetch(currentBearerToken());
        if (summary == null || summary.userId() == null || summary.ekycStatus() == null) {
            throw new BorrowerProfileUnavailableException(
                    "USER_RESPONSE_INVALID", "finora-user trả tóm tắt eKYC thiếu field bắt buộc", null);
        }
        if (!summary.userId().equals(borrowerId)) {
            // Token và hồ sơ User lệch người là lỗi cấu hình (claim user_id sai), không được dùng KYC của người khác.
            log.error("Tóm tắt eKYC không khớp người vay của request: borrowerId={}", borrowerId);
            throw new BorrowerProfileUnavailableException(
                    "USER_IDENTITY_MISMATCH", "Tóm tắt eKYC không thuộc người vay đang nộp hồ sơ", null);
        }
        return new BorrowerProfileResult(
                borrowerId,
                null,
                summary.age(),
                kycStatus(summary.ekycStatus()),
                "USER-KYC-" + borrowerId,
                KYC_VERSION,
                IncomeVerificationStatus.NOT_VERIFIED,
                BorrowerProfileSource.USER_SERVICE,
                Instant.now(clock));
    }

    /** Worker không có token người vay nên không gọi User được; hồ sơ phải có snapshot từ lúc nộp. */
    @Override
    public boolean supportsBackgroundLookup() {
        return false;
    }

    private UserKycSummary fetch(String bearerToken) {
        return circuitBreakerFactory.create(CIRCUIT_BREAKER).run(
                () -> restClient.get()
                        .uri(KYC_SUMMARY_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(UserKycSummary.class),
                throwable -> {
                    throw mapException(throwable);
                });
    }

    /**
     * Giữ đúng mã lỗi như khi chưa có circuit breaker. Circuit đang mở, TimeLimiter cắt cuộc gọi hoặc luồng chờ bị ngắt
     * cũng là không kết nối được finora-user; lỗi khác ném lại nguyên vẹn.
     */
    RuntimeException mapException(Throwable throwable) {
        if (throwable instanceof RestClientResponseException exception) {
            log.warn("finora-user từ chối đọc tóm tắt eKYC: status={}", exception.getStatusCode().value());
            return new BorrowerProfileUnavailableException(
                    "USER_HTTP_" + exception.getStatusCode().value(), "finora-user từ chối đọc tóm tắt eKYC", exception);
        }
        if (throwable instanceof InterruptedException) {
            // Luồng đang chờ TimeLimiter bị ngắt: trả lại cờ interrupt cho nơi gọi.
            Thread.currentThread().interrupt();
        }
        if (throwable instanceof ResourceAccessException
                || throwable instanceof CallNotPermittedException
                || throwable instanceof TimeoutException
                || throwable instanceof InterruptedException) {
            log.warn("Không kết nối được finora-user: exceptionType={}", throwable.getClass().getSimpleName());
            return unreachable(throwable);
        }
        if (throwable instanceof RestClientException exception) {
            return new BorrowerProfileUnavailableException(
                    "USER_RESPONSE_INVALID", "Không đọc được tóm tắt eKYC từ finora-user", exception);
        }
        if (throwable instanceof RuntimeException exception) {
            return exception;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        // Checked exception còn lại (ExecutionException không có cause) cũng là chưa đọc được từ finora-user.
        return unreachable(throwable);
    }

    private static BorrowerProfileUnavailableException unreachable(Throwable cause) {
        return new BorrowerProfileUnavailableException("USER_UNREACHABLE", "Không kết nối được finora-user", cause);
    }

    /** Trạng thái lạ thì dừng (fail closed) thay vì đoán người vay đã xác minh. */
    static BorrowerKycStatus kycStatus(String ekycStatus) {
        return switch (ekycStatus) {
            case "VERIFIED" -> BorrowerKycStatus.VERIFIED;
            case "PENDING" -> BorrowerKycStatus.PENDING;
            case "MANUAL_REVIEW" -> BorrowerKycStatus.PROCESSING;
            case "FAILED" -> BorrowerKycStatus.REJECTED;
            default -> throw new BorrowerProfileUnavailableException(
                    "USER_KYC_STATUS_UNKNOWN", "Trạng thái eKYC không được hỗ trợ: " + ekycStatus, null);
        };
    }

    private static String currentBearerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        throw new BorrowerProfileUnavailableException(
                "BORROWER_TOKEN_MISSING", "Không có token người vay để đọc tóm tắt eKYC", null);
    }
}
