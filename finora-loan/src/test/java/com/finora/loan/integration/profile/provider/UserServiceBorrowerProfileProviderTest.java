package com.finora.loan.integration.profile.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finora.loan.domain.scoring.BorrowerKycStatus;
import com.finora.loan.domain.scoring.BorrowerProfileSource;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** W2-KYC: Loan đọc eKYC bằng chính token của người vay, chỉ nhận trạng thái/tuổi và không đoán khi User lỗi. */
class UserServiceBorrowerProfileProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 17);
    private static final String SUMMARY_URL = "http://user.test/api/v1/users/me/kyc-summary";

    private MockRestServiceServer server;
    private UserServiceBorrowerProfileProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://user.test");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new UserServiceBorrowerProfileProvider(builder.build(), Clock.fixed(NOW, ZoneOffset.UTC));
        Jwt jwt = Jwt.withTokenValue("borrower-token").header("alg", "none")
                .subject("6f1c8a2e-0000-4000-8000-000000000042").claim("user_id", "42").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void relaysBorrowerTokenAndCapturesVerifiedSummary() {
        server.expect(requestTo(SUMMARY_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer borrower-token"))
                .andRespond(withSuccess("""
                        {"userId":"42","ekycStatus":"VERIFIED","documentVerified":true,
                         "ekycCompletedAt":"2026-10-01T02:00:00Z","age":27}
                        """, MediaType.APPLICATION_JSON));

        BorrowerProfileResult profile = provider.getBorrowerProfile("42", AS_OF);

        server.verify();
        assertThat(profile.borrowerId()).isEqualTo("42");
        assertThat(profile.kycStatus()).isEqualTo(BorrowerKycStatus.VERIFIED);
        assertThat(profile.age()).isEqualTo(27);
        // User không trả ngày sinh nên Loan không có để lưu.
        assertThat(profile.dateOfBirth()).isNull();
        assertThat(profile.source()).isEqualTo(BorrowerProfileSource.USER_SERVICE);
        assertThat(profile.kycVersion()).isEqualTo("USER_KYC_SUMMARY_V1");
        assertThat(profile.capturedAt()).isEqualTo(NOW);
    }

    @Test
    void mapsUserStatusesWithoutAssumingVerified() {
        assertThat(UserServiceBorrowerProfileProvider.kycStatus("PENDING")).isEqualTo(BorrowerKycStatus.PENDING);
        assertThat(UserServiceBorrowerProfileProvider.kycStatus("MANUAL_REVIEW")).isEqualTo(BorrowerKycStatus.PROCESSING);
        assertThat(UserServiceBorrowerProfileProvider.kycStatus("FAILED")).isEqualTo(BorrowerKycStatus.REJECTED);
        assertThatThrownBy(() -> UserServiceBorrowerProfileProvider.kycStatus("SOMETHING_NEW"))
                .isInstanceOf(BorrowerProfileUnavailableException.class)
                .extracting("reason").isEqualTo("USER_KYC_STATUS_UNKNOWN");
    }

    @Test
    void userOutageIsReportedAsUnavailableInsteadOfGuessing() {
        server.expect(requestTo(SUMMARY_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> provider.getBorrowerProfile("42", AS_OF))
                .isInstanceOf(BorrowerProfileUnavailableException.class)
                .extracting("reason").isEqualTo("USER_HTTP_500");
    }

    @Test
    void summaryOfAnotherUserIsRejected() {
        server.expect(requestTo(SUMMARY_URL)).andRespond(withSuccess("""
                {"userId":"43","ekycStatus":"VERIFIED","documentVerified":true,"ekycCompletedAt":null,"age":30}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.getBorrowerProfile("42", AS_OF))
                .isInstanceOf(BorrowerProfileUnavailableException.class)
                .extracting("reason").isEqualTo("USER_IDENTITY_MISMATCH");
    }

    @Test
    void workerWithoutBorrowerTokenCannotCallUser() {
        SecurityContextHolder.clearContext();

        assertThat(provider.supportsBackgroundLookup()).isFalse();
        assertThatThrownBy(() -> provider.getBorrowerProfile("42", AS_OF))
                .isInstanceOf(BorrowerProfileUnavailableException.class)
                .extracting("reason").isEqualTo("BORROWER_TOKEN_MISSING");
    }
}
