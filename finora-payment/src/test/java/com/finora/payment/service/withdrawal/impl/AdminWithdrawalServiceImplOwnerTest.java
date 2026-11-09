package com.finora.payment.service.withdrawal.impl;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.ops.OpsFunction;
import com.finora.payment.domain.withdrawal.enums.WithdrawalStatus;
import com.finora.payment.repository.withdrawal.PaymentWithdrawalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Lệnh rút chờ đối soát theo khách hàng (trang chi tiết khách hàng): chỉ đọc đúng chủ ví, vẫn cần quyền Xem rút tiền. */
class AdminWithdrawalServiceImplOwnerTest {

    private final PaymentWithdrawalRepository withdrawals = mock(PaymentWithdrawalRepository.class);
    private final AdminWithdrawalServiceImpl service = new AdminWithdrawalServiceImpl(withdrawals, Clock.systemUTC());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listOfOneOwnerReadsOnlyTheirWithdrawals() {
        actAs(OpsFunction.WITHDRAWAL.view());
        when(withdrawals.findByOwnerIdAndStatusInOrderByUpdatedAtAscIdAsc(eq("42"), anyCollection(),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        var response = service.list(" 42 ", 0, 20);

        assertThat(response.getContent()).isEmpty();
        verify(withdrawals).findByOwnerIdAndStatusInOrderByUpdatedAtAscIdAsc(eq("42"),
                eq(List.of(WithdrawalStatus.PROCESSING, WithdrawalStatus.PROVIDER_PENDING,
                        WithdrawalStatus.RECONCILIATION_REQUIRED)), any(Pageable.class));
        verify(withdrawals, never()).findByStatusInOrderByUpdatedAtAscIdAsc(anyCollection(), any(Pageable.class));
    }

    @Test
    void blankOwnerKeepsTheGlobalQueue() {
        actAs(OpsFunction.WITHDRAWAL.view());
        when(withdrawals.findByStatusInOrderByUpdatedAtAscIdAsc(anyCollection(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.list("  ", 0, 20);

        verify(withdrawals).findByStatusInOrderByUpdatedAtAscIdAsc(anyCollection(), any(Pageable.class));
    }

    @Test
    void ownerFilterStillNeedsWithdrawalView() {
        actAs(OpsFunction.RECONCILIATION.view());

        assertThatThrownBy(() -> service.list("42", 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PERMISSION_REQUIRED"));
    }

    private static void actAs(String... authorities) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("sub", "staff-1").claim("user_id", "staff-1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
