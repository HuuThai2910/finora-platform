package com.finora.loan.security;

import com.finora.loan.exception.LoanBusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserProviderTest {

    private final CurrentUserProvider provider = new CurrentUserProvider();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void borrowerIdComesFromUserIdClaim() {
        authenticate(jwtWithClaim("42"), "ROLE_BORROWER");

        assertThat(provider.borrowerUserId()).isEqualTo("42");
    }

    @Test
    void adminIdComesFromUserIdClaimWhenCallerHasAdminRole() {
        authenticate(jwtWithClaim("7"), "ROLE_ADMIN");

        assertThat(provider.adminUserId()).isEqualTo("7");
    }

    @Test
    void adminIdIsRejectedForNonAdminCaller() {
        authenticate(jwtWithClaim("42"), "ROLE_BORROWER");

        assertThatThrownBy(provider::adminUserId)
                .isInstanceOf(LoanBusinessException.class)
                .satisfies(e -> assertThat(((LoanBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    /** Token hợp lệ nhưng thiếu claim là lỗi cấu hình Keycloak, không phải hết phiên. */
    @Test
    void missingClaimIsReportedAsUnauthorized() {
        authenticate(jwtWithoutClaim(), "ROLE_BORROWER");

        assertThatThrownBy(provider::borrowerUserId)
                .isInstanceOf(LoanBusinessException.class)
                .satisfies(e -> assertThat(((LoanBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void blankClaimIsTreatedAsMissing() {
        authenticate(jwtWithClaim("   "), "ROLE_BORROWER");

        assertThatThrownBy(provider::borrowerUserId)
                .isInstanceOf(LoanBusinessException.class);
    }

    @Test
    void requestWithoutAuthenticationIsRejected() {
        assertThatThrownBy(provider::borrowerUserId)
                .isInstanceOf(LoanBusinessException.class)
                .satisfies(e -> assertThat(((LoanBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    /** Principal không phải JWT (ví dụ phiên form login sót lại) không được coi là hợp lệ. */
    @Test
    void nonJwtPrincipalIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_BORROWER"))));

        assertThatThrownBy(provider::borrowerUserId)
                .isInstanceOf(LoanBusinessException.class);
    }

    private void authenticate(Jwt jwt, String authority) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority(authority)), jwt.getSubject()));
    }

    private Jwt jwtWithClaim(String userId) {
        return baseJwt().claim("user_id", userId).build();
    }

    private Jwt jwtWithoutClaim() {
        return baseJwt().build();
    }

    private Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("2a4d1e6c-0000-4000-8000-000000000001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claims(c -> c.put("email", "nguoivay@example.com"));
    }
}
