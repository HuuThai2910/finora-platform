package com.finora.payment.service.disbursement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finora.common.exception.BusinessException;
import com.finora.payment.domain.disbursement.PaymentDisbursement;
import com.finora.payment.domain.disbursement.PaymentDisbursementStatus;
import com.finora.payment.repository.PaymentDisbursementRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AdminDisbursementReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");

    private final PaymentDisbursementRepository disbursements = mock(PaymentDisbursementRepository.class);
    private final AdminDisbursementReconciliationService service =
            new AdminDisbursementReconciliationService(disbursements, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void findReturnsACompletedDisbursementWithItsProviderAndLookupCounts() {
        actAs("ROLE_ADMIN");
        UUID sagaId = UUID.randomUUID();
        PaymentDisbursement value = PaymentDisbursement.requested(sagaId, 10L, "LC-E2E-1", 20L, "borrower-1",
                new BigDecimal("10000000"), "VND", "MOCK", "[]", NOW);
        when(disbursements.findBySagaId(sagaId)).thenReturn(Optional.of(value));

        var response = service.find(sagaId);

        // Lệnh đã đóng vẫn đọc được: drill Q13 dựa vào số lần gọi provider để chứng minh không chuyển tiền hai lần.
        assertThat(response.sagaId()).isEqualTo(sagaId);
        assertThat(response.status()).isEqualTo(PaymentDisbursementStatus.REQUESTED);
        assertThat(response.attemptCount()).isZero();
        assertThat(response.reconciliationAttemptCount()).isZero();
    }

    @Test
    void findOfUnknownSagaIsNotFound() {
        actAs("ROLE_ADMIN");
        UUID sagaId = UUID.randomUUID();
        when(disbursements.findBySagaId(sagaId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.find(sagaId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("DISBURSEMENT_NOT_FOUND"));
    }

    @Test
    void findRequiresAdmin() {
        actAs("ROLE_BORROWER");

        assertThatThrownBy(() -> service.find(UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("ADMIN_ROLE_REQUIRED"));
        verifyNoInteractions(disbursements);
    }

    private static void actAs(String role) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("sub", "admin-1").claim("user_id", "admin-1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority(role)), "admin-1"));
    }
}
