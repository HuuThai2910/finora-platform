package com.finora.payment.service.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.common.exception.BusinessException;
import com.finora.payment.domain.hold.PaymentHold;
import com.finora.payment.domain.hold.PaymentHoldStatus;
import com.finora.payment.domain.wallet.PaymentWallet;
import com.finora.payment.domain.wallet.WalletOwnerType;
import com.finora.payment.dto.request.CreateHoldRequest;
import com.finora.payment.dto.response.HoldResponse;
import com.finora.payment.repository.hold.PaymentHoldRepository;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import com.finora.payment.service.ledger.LedgerPostingService;
import com.finora.payment.service.wallet.WalletAccountService;
import com.finora.payment.service.wallet.WalletView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Token service account mang {@code payment:hold:on_behalf} (Auto-Invest của finora-investment)
 * giữ/nhả được tiền thay nhà đầu tư; token người dùng thường thì không.
 */
class HoldTransferServiceOnBehalfTest {

    private static final Instant NOW = Instant.parse("2026-09-30T08:00:00Z");

    private final PaymentHoldRepository holdRepository = mock(PaymentHoldRepository.class);
    private final PaymentWalletRepository walletRepository = mock(PaymentWalletRepository.class);
    private final WalletAccountService walletAccountService = mock(WalletAccountService.class);
    private final LedgerPostingService ledgerPostingService = mock(LedgerPostingService.class);
    private HoldTransferService service;
    private PaymentWallet wallet;

    @BeforeEach
    void setUp() {
        service = new HoldTransferService(holdRepository, walletRepository, walletAccountService,
                ledgerPostingService, Clock.fixed(NOW, ZoneOffset.UTC));
        wallet = PaymentWallet.open(UUID.randomUUID(), WalletOwnerType.INVESTOR, "INV-001", "VND", NOW);
        when(walletAccountService.open(WalletOwnerType.INVESTOR, "INV-001", "VND"))
                .thenReturn(WalletView.from(wallet));
        when(walletRepository.findByWalletId(wallet.getWalletId())).thenReturn(Optional.of(wallet));
        when(holdRepository.findByOrderReference(any())).thenReturn(Optional.empty());
        when(holdRepository.save(any(PaymentHold.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(String subject, String userId, String authority) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").claim("sub", subject);
        if (userId != null) {
            builder.claim("user_id", userId);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                builder.build(), List.of(new SimpleGrantedAuthority(authority)), subject));
    }

    @Test
    @DisplayName("Service account on_behalf giữ tiền vào ví của investorId trong request")
    void serviceAccountHoldsForInvestor() {
        actAs("service-account-finora-investment-client", null, HoldTransferService.ON_BEHALF_AUTHORITY);

        HoldResponse response = service.hold(new CreateHoldRequest("INV-001", new BigDecimal("2000000.00"), "IO-1"));

        assertThat(response.holdReference()).startsWith("HOLD-");
        verify(walletAccountService).open(WalletOwnerType.INVESTOR, "INV-001", "VND");
        verify(ledgerPostingService).post(any());
    }

    @Test
    @DisplayName("Nhà đầu tư thường không giữ được tiền của người khác")
    void investorCannotHoldForAnotherInvestor() {
        actAs("kc-2", "INV-002", "ROLE_INVESTOR");

        assertThatThrownBy(() -> service.hold(
                new CreateHoldRequest("INV-001", new BigDecimal("2000000.00"), "IO-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Không được thao tác ví của người dùng khác");
        verify(ledgerPostingService, never()).post(any());
    }

    @Test
    @DisplayName("Service account on_behalf nhả tiền giữ của nhà đầu tư")
    void serviceAccountReleasesForOwner() {
        PaymentHold hold = PaymentHold.create(UUID.randomUUID(), "HOLD-X", "IO-3", wallet,
                new BigDecimal("2000000.00"), NOW);
        when(holdRepository.findByHoldReferenceForUpdate("HOLD-X")).thenReturn(Optional.of(hold));
        actAs("service-account-finora-investment-client", null, HoldTransferService.ON_BEHALF_AUTHORITY);

        service.release("HOLD-X", "IO-3");

        assertThat(hold.getStatus()).isEqualTo(PaymentHoldStatus.RELEASED);
        verify(ledgerPostingService).post(any());
    }
}
