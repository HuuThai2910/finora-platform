package com.finora.common.security;

import com.finora.common.exception.BusinessException;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityUtilsTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userIdComesFromUserIdClaim() {
        authenticate(jwtWithClaim("42"), "ROLE_BORROWER");

        assertThat(SecurityUtils.getCurrentUserId()).isEqualTo("42");
    }

    @Test
    void fallbackToSubjectIfNoUserIdClaim() {
        authenticate(jwtWithoutClaim(), "ROLE_BORROWER");

        assertThat(SecurityUtils.getCurrentUserId()).isEqualTo("2a4d1e6c-0000-4000-8000-000000000001");
    }

    @Test
    void keycloakUserIdParsedFromSubject() {
        authenticate(jwtWithoutClaim(), "ROLE_BORROWER");

        assertThat(SecurityUtils.getCurrentKeycloakUserId())
                .isEqualTo(UUID.fromString("2a4d1e6c-0000-4000-8000-000000000001"));
    }

    @Test
    void emailAndUsernameExtractedCorrectly() {
        authenticate(baseJwt().claim("email", "test@example.com").claim("preferred_username", "testuser").build(), "ROLE_BORROWER");

        assertThat(SecurityUtils.getCurrentEmail()).isEqualTo("test@example.com");
        assertThat(SecurityUtils.getCurrentUsername()).isEqualTo("testuser");
    }

    @Test
    void adminAuthorityCheckedCorrectly() {
        authenticate(jwtWithClaim("7"), "ROLE_ADMIN");

        assertThat(SecurityUtils.isAdmin()).isTrue();
        SecurityUtils.requireAdmin(); // Should not throw
    }

    @Test
    void nonAdminFailsRequireAdmin() {
        authenticate(jwtWithClaim("42"), "ROLE_BORROWER");

        assertThat(SecurityUtils.isAdmin()).isFalse();
        assertThatThrownBy(SecurityUtils::requireAdmin)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void requestWithoutAuthenticationIsRejected() {
        assertThatThrownBy(SecurityUtils::getCurrentUserId)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void nonJwtPrincipalIsRejected() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_BORROWER"))));

        assertThatThrownBy(SecurityUtils::getCurrentUserId)
                .isInstanceOf(BusinessException.class);
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
