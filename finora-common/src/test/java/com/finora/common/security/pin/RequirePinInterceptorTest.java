package com.finora.common.security.pin;

import com.finora.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequirePinInterceptorTest {

    private static final String USER = "2a4d1e6c-0000-4000-8000-000000000001";

    private final PinTokenService tokens = new PinTokenService(
            "test-pin-token-secret-at-least-32-bytes!", Duration.ofMinutes(3), Clock.systemUTC());
    private final RequirePinInterceptor interceptor = new RequirePinInterceptor(tokens);

    @BeforeEach
    void authenticate() {
        Jwt jwt = Jwt.withTokenValue("access")
                .header("alg", "RS256")
                .subject(USER)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unguardedEndpointPassesWithoutToken() throws Exception {
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                handler("open"))).isTrue();
    }

    @Test
    void guardedEndpointWithoutTokenRejected() {
        assertThatThrownBy(() -> interceptor.preHandle(new MockHttpServletRequest(),
                new MockHttpServletResponse(), handler("placeOrder")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("PIN_REQUIRED");
    }

    @Test
    void guardedEndpointWithMatchingTokenPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(PinTokenService.HEADER_NAME, tokens.issue(USER, PinScope.ORDER).token());

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler("placeOrder"))).isTrue();
    }

    @Test
    void guardedEndpointWithTokenForAnotherScopeRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(PinTokenService.HEADER_NAME, tokens.issue(USER, PinScope.INVEST).token());

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(),
                handler("placeOrder")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("PIN_TOKEN_INVALID");
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new SampleController(), SampleController.class.getMethod(name));
    }

    static class SampleController {
        public void open() {
        }

        @RequirePin(PinScope.ORDER)
        public void placeOrder() {
        }
    }
}
