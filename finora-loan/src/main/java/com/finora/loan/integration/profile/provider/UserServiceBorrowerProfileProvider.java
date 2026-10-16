package com.finora.loan.integration.profile.provider;

import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.domain.scoring.IncomeVerificationStatus;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import com.finora.loan.integration.profile.contract.UserKycSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

/**
 * Đọc trạng thái eKYC và tuổi người vay từ finora-user bằng chính token của người vay (W2-KYC, quyết định D4).
 *
 * <p>Chuyển tiếp token thay vì dùng service account: người vay tự cho phép đọc dữ liệu của mình, Loan không cần quyền đọc
 * hồ sơ người khác và không có secret dùng chung mới. Vì cần token nên chỉ gọi được trong request nộp hồ sơ.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "finora.borrower-profile.provider", havingValue = "user")
public class UserServiceBorrowerProfileProvider implements BorrowerProfileProvider {

    static final String KYC_SUMMARY_PATH = "/api/v1/users/me/kyc-summary";
    static final String KYC_VERSION = "USER_KYC_SUMMARY_V1";

    private final RestClient restClient;
    private final Clock clock;

    public UserServiceBorrowerProfileProvider(@Qualifier("userRestClient") RestClient restClient, Clock clock) {
        this.restClient = restClient;
        this.clock = clock;
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
        try {
            return restClient.get()
                    .uri(KYC_SUMMARY_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(UserKycSummary.class);
        } catch (RestClientResponseException exception) {
            log.warn("finora-user từ chối đọc tóm tắt eKYC: status={}", exception.getStatusCode().value());
            throw new BorrowerProfileUnavailableException(
                    "USER_HTTP_" + exception.getStatusCode().value(), "finora-user từ chối đọc tóm tắt eKYC", exception);
        } catch (ResourceAccessException exception) {
            log.warn("Không kết nối được finora-user: exceptionType={}", exception.getClass().getSimpleName());
            throw new BorrowerProfileUnavailableException("USER_UNREACHABLE", "Không kết nối được finora-user", exception);
        } catch (RestClientException exception) {
            throw new BorrowerProfileUnavailableException(
                    "USER_RESPONSE_INVALID", "Không đọc được tóm tắt eKYC từ finora-user", exception);
        }
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
