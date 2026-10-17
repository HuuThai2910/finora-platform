package com.finora.payment.service.topup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.environment.FinoraEnvironment;
import com.finora.common.exception.BusinessException;
import com.finora.payment.config.TopUpProperties;
import com.finora.payment.domain.topup.PaymentTopUp;
import com.finora.payment.domain.wallet.PaymentWallet;
import com.finora.payment.domain.wallet.WalletOwnerType;
import com.finora.payment.dto.response.TopUpResponse;
import com.finora.payment.integration.topup.TopUpProvider;
import com.finora.payment.repository.topup.PaymentTopUpRepository;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import com.finora.payment.service.ledger.LedgerPostingService;
import com.finora.payment.service.wallet.WalletAccountService;
import com.finora.payment.service.wallet.WalletOwnerResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D37: nút "Giả lập thanh toán thành công" (tiền sandbox) chỉ tồn tại ở local/e2e/demo với provider nạp tiền mock, và
 * không ghi có lệnh đã quá hạn thanh toán.
 */
class TopUpServiceMockCompletionTest {

    private static final Instant NOW = Instant.parse("2026-10-17T09:00:00Z");
    private static final String OWNER = "INV-1";

    private final PaymentTopUpRepository topUps = mock(PaymentTopUpRepository.class);
    private final LedgerPostingService ledger = mock(LedgerPostingService.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1").claim("user_id", OWNER).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        when(transactions.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void stagingHasNoMockCompletion() {
        assertUnavailable(service(FinoraEnvironment.STAGING, "mock"));
    }

    @Test
    void realTopUpProviderHasNoMockCompletionEvenLocally() {
        assertUnavailable(service(FinoraEnvironment.LOCAL, "zalopay"));
    }

    @Test
    void expiredTopUpIsNotCredited() {
        PaymentTopUp topUp = awaitingMockTopUp(NOW.minus(Duration.ofMinutes(1)));
        when(topUps.findByTopUpIdForUpdate(topUp.getTopUpId())).thenReturn(Optional.of(topUp));

        assertThatThrownBy(() -> service(FinoraEnvironment.E2E, "mock").completeMock(topUp.getTopUpId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("TOPUP_EXPIRED"));
        verifyNoInteractions(ledger);
    }

    @Test
    void appOnlyShowsTheButtonWhenTheEnvironmentAllowsItAndTheTopUpIsStillOpen() {
        PaymentTopUp open = awaitingMockTopUp(NOW.plus(Duration.ofMinutes(15)));
        PaymentTopUp expired = awaitingMockTopUp(NOW.minus(Duration.ofSeconds(1)));

        assertThat(TopUpResponse.from(open, true, NOW).mockCompletionAvailable()).isTrue();
        assertThat(TopUpResponse.from(open, false, NOW).mockCompletionAvailable()).isFalse();
        assertThat(TopUpResponse.from(expired, true, NOW).mockCompletionAvailable()).isFalse();
    }

    private void assertUnavailable(TopUpService service) {
        assertThatThrownBy(() -> service.completeMock(UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("TOPUP_MOCK_COMPLETION_UNAVAILABLE");
                    assertThat(exception.getStatus().value()).isEqualTo(404);
                });
        verifyNoInteractions(topUps, ledger);
    }

    private PaymentTopUp awaitingMockTopUp(Instant expiresAt) {
        PaymentWallet wallet = PaymentWallet.open(UUID.randomUUID(), WalletOwnerType.INVESTOR, OWNER, "VND", NOW);
        PaymentTopUp topUp = PaymentTopUp.prepare(UUID.randomUUID(), wallet, new BigDecimal("1000000"), "MOCK",
                "261017_order", "idem-topup-1", "a".repeat(64), NOW.minus(Duration.ofMinutes(20)));
        topUp.awaitingPayment(null, null, expiresAt, NOW.minus(Duration.ofMinutes(20)));
        return topUp;
    }

    private TopUpService service(FinoraEnvironment environment, String provider) {
        return new TopUpService(topUps, mock(PaymentWalletRepository.class), mock(WalletAccountService.class),
                mock(WalletOwnerResolver.class), ledger, mock(TopUpProvider.class),
                new TopUpProperties(provider, null, null), null, new ObjectMapper(), transactions,
                Clock.fixed(NOW, ZoneOffset.UTC), environment);
    }
}
