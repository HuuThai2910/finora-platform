package com.finora.common.security.pin;

import com.finora.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PinTokenServiceTest {

    private static final String SECRET = "test-pin-token-secret-at-least-32-bytes!";
    private static final String USER = "2a4d1e6c-0000-4000-8000-000000000001";
    private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");

    private final PinTokenService service = at(NOW);

    @Test
    void tokenIssuedForUserAndScopePassesVerification() {
        PinTokenService.IssuedPinToken issued = service.issue(USER, PinScope.ORDER);

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
        service.verify(issued.token(), USER, PinScope.ORDER);
    }

    @Test
    void tokenRejectedForAnotherScope() {
        String token = service.issue(USER, PinScope.ORDER).token();

        assertInvalid(() -> service.verify(token, USER, PinScope.REPAYMENT));
    }

    @Test
    void tokenRejectedForAnotherUser() {
        String token = service.issue(USER, PinScope.ORDER).token();

        assertInvalid(() -> service.verify(token, "someone-else", PinScope.ORDER));
    }

    @Test
    void tokenRejectedAfterExpiry() {
        String token = service.issue(USER, PinScope.ORDER).token();

        assertInvalid(() -> at(NOW.plus(Duration.ofMinutes(4))).verify(token, USER, PinScope.ORDER));
    }

    @Test
    void tokenSignedWithAnotherSecretRejected() {
        PinTokenService other = new PinTokenService(
                "another-secret-that-is-also-32-bytes-long", Duration.ofMinutes(3), clock(NOW));
        String token = other.issue(USER, PinScope.ORDER).token();

        assertInvalid(() -> service.verify(token, USER, PinScope.ORDER));
    }

    @Test
    void garbageTokenRejected() {
        assertInvalid(() -> service.verify("not-a-jwt", USER, PinScope.ORDER));
    }

    @Test
    void missingSecretFailsClosed() {
        PinTokenService unconfigured = new PinTokenService("", Duration.ofMinutes(3), clock(NOW));

        assertThatThrownBy(() -> unconfigured.verify("x", USER, PinScope.ORDER))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("PIN_NOT_CONFIGURED");
    }

    @Test
    void shortSecretRejectedAtStartup() {
        assertThatThrownBy(() -> new PinTokenService("short", Duration.ofMinutes(3), clock(NOW)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static PinTokenService at(Instant instant) {
        return new PinTokenService(SECRET, Duration.ofMinutes(3), clock(instant));
    }

    private static Clock clock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("PIN_TOKEN_INVALID");
    }
}
