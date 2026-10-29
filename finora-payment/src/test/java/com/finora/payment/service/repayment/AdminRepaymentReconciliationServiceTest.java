package com.finora.payment.service.repayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.ops.OpsFunction;
import com.finora.payment.domain.repayment.PaymentRepayment;
import com.finora.payment.domain.repayment.PaymentRepaymentStatus;
import com.finora.payment.domain.servicing.PaymentLoanAccount;
import com.finora.payment.integration.fineract.FineractRepaymentException;
import com.finora.payment.repository.repayment.PaymentRepaymentRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

class AdminRepaymentReconciliationServiceTest {

    private final PaymentRepaymentRepository repayments = org.mockito.Mockito.mock(PaymentRepaymentRepository.class);
    private final PaymentRepaymentService repaymentService = org.mockito.Mockito.mock(PaymentRepaymentService.class);
    private AdminRepaymentReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new AdminRepaymentReconciliationService(repayments, repaymentService, new MockEnvironment());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listReturnsOnlyOperationalIncidentStatuses() {
        actAs("ROLE_ADMIN");
        PaymentRepayment repayment = reconciliationRequiredRepayment();
        when(repayments.findByStatusInOrderByUpdatedAtAscIdAsc(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(repayment)));

        var response = service.list(null, 0, 20);

        assertThat(response.getContent()).singleElement().satisfies(item -> {
            assertThat(item.repaymentId()).isEqualTo(repayment.getRepaymentId());
            assertThat(item.status()).isEqualTo(PaymentRepaymentStatus.RECONCILIATION_REQUIRED);
        });
        ArgumentCaptor<List<PaymentRepaymentStatus>> statuses = ArgumentCaptor.forClass(List.class);
        verify(repayments).findByStatusInOrderByUpdatedAtAscIdAsc(
                statuses.capture(), org.mockito.ArgumentMatchers.any(Pageable.class));
        assertThat(statuses.getValue()).containsExactly(
                PaymentRepaymentStatus.CORE_POSTING,
                PaymentRepaymentStatus.RECONCILIATION_REQUIRED,
                PaymentRepaymentStatus.FAILED);
    }

    @Test
    void listCanBeNarrowedToOpenWorkButNeverOutsideTheIncidentQueue() {
        actAs("ROLE_ADMIN");
        when(repayments.findByStatusInOrderByUpdatedAtAscIdAsc(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.list(List.of(PaymentRepaymentStatus.CORE_POSTING, PaymentRepaymentStatus.RECONCILIATION_REQUIRED), 0, 20);

        ArgumentCaptor<List<PaymentRepaymentStatus>> statuses = ArgumentCaptor.forClass(List.class);
        verify(repayments).findByStatusInOrderByUpdatedAtAscIdAsc(
                statuses.capture(), org.mockito.ArgumentMatchers.any(Pageable.class));
        assertThat(statuses.getValue()).containsExactly(
                PaymentRepaymentStatus.CORE_POSTING, PaymentRepaymentStatus.RECONCILIATION_REQUIRED);
        assertThatThrownBy(() -> service.list(List.of(PaymentRepaymentStatus.COMPLETED), 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("REPAYMENT_INCIDENT_STATUS_INVALID"));
    }

    @Test
    void repostRunsAsTheAdminWithTheSafeWindowAndShowsWhenItBecomesAvailable() {
        actAs("ROLE_ADMIN");
        PaymentRepayment repayment = reconciliationRequiredRepayment();
        when(repayments.findByRepaymentId(repayment.getRepaymentId())).thenReturn(Optional.of(repayment));

        var response = service.repost(repayment.getRepaymentId());

        verify(repaymentService).repostCorePosting(42L, "admin-1", Duration.ofMinutes(15));
        // Mock service không đổi trạng thái: khoản vẫn chờ đối soát, lúc được ghi lại = lần đổi trạng thái + 15 phút.
        assertThat(response.coreRepostAvailableAt()).isEqualTo(Instant.parse("2026-10-04T02:16:00Z"));
        assertThat(response.corePostingDate()).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    @Test
    void repostThatCannotReadTheCoreLeavesTheRepaymentUntouchedAndAsksToRetry() {
        actAs("ROLE_ADMIN");
        PaymentRepayment repayment = reconciliationRequiredRepayment();
        when(repayments.findByRepaymentId(repayment.getRepaymentId())).thenReturn(Optional.of(repayment));
        doThrow(new FineractRepaymentException("FINERACT_RECONCILIATION_UNAVAILABLE", "timeout", true, null))
                .when(repaymentService).repostCorePosting(42L, "admin-1", Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.repost(repayment.getRepaymentId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("FINERACT_RECONCILIATION_UNAVAILABLE"));
        assertThat(repayment.getStatus()).isEqualTo(PaymentRepaymentStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    void reconcileUsesReadOnlyRecoveryPathForUnknownCoreOutcome() {
        actAs("ROLE_ADMIN");
        PaymentRepayment repayment = reconciliationRequiredRepayment();
        when(repayments.findByRepaymentId(repayment.getRepaymentId())).thenReturn(Optional.of(repayment));

        var response = service.reconcile(repayment.getRepaymentId());

        verify(repaymentService).reconcileCorePosting(42L);
        assertThat(response.status()).isEqualTo(PaymentRepaymentStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    void reconcileRejectsNonReconciliationStatus() {
        actAs("ROLE_ADMIN");
        PaymentRepayment repayment = collectedRepayment();
        when(repayments.findByRepaymentId(repayment.getRepaymentId())).thenReturn(Optional.of(repayment));

        assertThatThrownBy(() -> service.reconcile(repayment.getRepaymentId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("REPAYMENT_NOT_RECONCILABLE"));
        verify(repaymentService, never()).reconcileCorePosting(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void nonAdminCannotReadOrTriggerReconciliation() {
        actAs("ROLE_BORROWER");

        assertThatThrownBy(() -> service.list(null, 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PERMISSION_REQUIRED"));
        verify(repayments, never()).findByStatusInOrderByUpdatedAtAscIdAsc(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(Pageable.class));
    }

    private PaymentRepayment reconciliationRequiredRepayment() {
        PaymentRepayment repayment = collectedRepayment();
        repayment.startCorePosting(Instant.parse("2026-10-04T02:00:00Z"));
        repayment.requireReconciliation("FINERACT_TIMEOUT", "Không xác định được kết quả", 
                Instant.parse("2026-10-04T02:01:00Z"));
        return repayment;
    }

    private PaymentRepayment collectedRepayment() {
        PaymentLoanAccount account = PaymentLoanAccount.activate(10L, "LC-10", 20L,
                "BORROWER-10", 30L, "VND", Instant.parse("2026-10-01T00:00:00Z"));
        PaymentRepayment repayment = PaymentRepayment.collected("idem-10", account,
                new BigDecimal("1000000.00"), LocalDate.of(2026, 10, 4), UUID.randomUUID(),
                Instant.parse("2026-10-04T01:00:00Z"));
        ReflectionTestUtils.setField(repayment, "id", 42L);
        return repayment;
    }

    @Test
    void staffWithReconciliationViewListsButCannotReconcileOrRepost() {
        actAs("ROLE_STAFF", OpsFunction.RECONCILIATION.view());
        when(repayments.findByStatusInOrderByUpdatedAtAscIdAsc(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(service.list(null, 0, 20).getContent()).isEmpty();
        UUID repaymentId = UUID.randomUUID();
        assertThatThrownBy(() -> service.reconcile(repaymentId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PERMISSION_REQUIRED"));
        assertThatThrownBy(() -> service.repost(repaymentId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PERMISSION_REQUIRED"));
        verify(repaymentService, never()).reconcileCorePosting(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void staffWithReconciliationActionPassesThePermissionCheck() {
        actAs("ROLE_STAFF", OpsFunction.RECONCILIATION.action());
        UUID repaymentId = UUID.randomUUID();
        when(repayments.findByRepaymentId(repaymentId)).thenReturn(Optional.empty());

        // Qua kiểm quyền, dừng ở tra cứu: giao dịch không tồn tại.
        assertThatThrownBy(() -> service.reconcile(repaymentId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("REPAYMENT_NOT_FOUND"));
    }

    private void actAs(String... authorities) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("sub", "admin-1").claim("user_id", "admin-1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList(), "admin-1"));
    }
}
