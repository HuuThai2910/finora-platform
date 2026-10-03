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
import com.finora.payment.domain.ledger.LedgerBalanceBucket;
import com.finora.payment.domain.ledger.LedgerDirection;
import com.finora.payment.domain.ledger.LedgerTransactionStatus;
import com.finora.payment.domain.wallet.PaymentWallet;
import com.finora.payment.domain.wallet.WalletOwnerType;
import com.finora.payment.dto.request.SettleHoldRequest;
import com.finora.payment.repository.hold.PaymentHoldRepository;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import com.finora.payment.service.ledger.LedgerPostingCommand;
import com.finora.payment.service.ledger.LedgerPostingEntryCommand;
import com.finora.payment.service.ledger.LedgerPostingResult;
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
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Thanh toán từng phần từ khoản giữ cho sổ lệnh Notes: trừ đúng phần giữ, chuyển cho người bán sau
 * phí, gọi lại không trừ hai lần, không vượt số giữ, và nhả về đúng phần còn lại.
 */
class HoldSettlementTest {

    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");

    private final PaymentHoldRepository holdRepository = mock(PaymentHoldRepository.class);
    private final PaymentWalletRepository walletRepository = mock(PaymentWalletRepository.class);
    private final WalletAccountService walletAccountService = mock(WalletAccountService.class);
    private final LedgerPostingService ledgerPostingService = mock(LedgerPostingService.class);
    private HoldTransferService service;
    private PaymentWallet sellerWallet;
    private PaymentHold hold;

    @BeforeEach
    void setUp() {
        service = new HoldTransferService(holdRepository, walletRepository, walletAccountService,
                ledgerPostingService, Clock.fixed(NOW, ZoneOffset.UTC));
        PaymentWallet buyerWallet = PaymentWallet.open(UUID.randomUUID(), WalletOwnerType.INVESTOR, "BUYER", "VND", NOW);
        sellerWallet = PaymentWallet.open(UUID.randomUUID(), WalletOwnerType.INVESTOR, "SELLER", "VND", NOW);
        hold = PaymentHold.create(UUID.randomUUID(), "HOLD-B", "OB-1", buyerWallet, new BigDecimal("2910000.00"), NOW);
        when(holdRepository.findByHoldReferenceForUpdate("HOLD-B")).thenReturn(Optional.of(hold));
        when(walletAccountService.open(WalletOwnerType.INVESTOR, "SELLER", "VND")).thenReturn(WalletView.from(sellerWallet));
        actAs(HoldTransferService.ON_BEHALF_AUTHORITY);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(String authority) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("sub", "svc").claim("user_id", "svc").build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority)), "svc"));
    }

    private void ledgerReturns(boolean replayed) {
        when(ledgerPostingService.post(any())).thenReturn(new LedgerPostingResult(
                UUID.randomUUID(), LedgerTransactionStatus.POSTED, BigDecimal.ONE, NOW, replayed));
    }

    private static SettleHoldRequest request(String amount, String fee, String reference) {
        return new SettleHoldRequest("OB-1", "SELLER", new BigDecimal(amount), new BigDecimal(fee), reference);
    }

    @Test
    @DisplayName("Thanh toán một phần: trừ HELD người mua, cộng người bán sau phí, phí vào PLATFORM_FEE")
    void settlesPartOfHold() {
        ledgerReturns(false);

        service.settleFromHold("HOLD-B", request("970000.00", "48500.00", "TRD-1"));

        ArgumentCaptor<LedgerPostingCommand> posted = ArgumentCaptor.forClass(LedgerPostingCommand.class);
        verify(ledgerPostingService).post(posted.capture());
        List<LedgerPostingEntryCommand> entries = posted.getValue().entries();
        assertThat(posted.getValue().idempotencyKey()).isEqualTo("HOLD_SETTLE:TRD-1");
        assertThat(entries).extracting(LedgerPostingEntryCommand::balanceBucket, LedgerPostingEntryCommand::direction)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LedgerBalanceBucket.HELD, LedgerDirection.DEBIT),
                        org.assertj.core.groups.Tuple.tuple(LedgerBalanceBucket.AVAILABLE, LedgerDirection.CREDIT),
                        org.assertj.core.groups.Tuple.tuple(LedgerBalanceBucket.CLEARING, LedgerDirection.CREDIT));
        assertThat(entries.get(1).walletId()).isEqualTo(sellerWallet.getWalletId());
        assertThat(entries.get(1).amount()).isEqualByComparingTo("921500.00");
        assertThat(hold.remainingAmount()).isEqualByComparingTo("1940000.00");
        assertThat(hold.getStatus()).isEqualTo(PaymentHoldStatus.HELD);
    }

    @Test
    @DisplayName("Gọi lại cùng mã thanh toán không trừ khoản giữ lần hai")
    void replayDoesNotSettleTwice() {
        ledgerReturns(false);
        service.settleFromHold("HOLD-B", request("970000.00", "48500.00", "TRD-1"));
        ledgerReturns(true);
        service.settleFromHold("HOLD-B", request("970000.00", "48500.00", "TRD-1"));

        assertThat(hold.remainingAmount()).isEqualByComparingTo("1940000.00");
    }

    @Test
    @DisplayName("Không thanh toán vượt phần còn đang giữ")
    void rejectsOverdraw() {
        ledgerReturns(false);

        assertThatThrownBy(() -> service.settleFromHold("HOLD-B", request("2910000.01", "0", "TRD-2")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("không đủ");
        assertThat(hold.remainingAmount()).isEqualByComparingTo("2910000.00");
    }

    @Test
    @DisplayName("Token nhà đầu tư thường không thanh toán được từ khoản giữ")
    void investorTokenForbidden() {
        actAs("ROLE_INVESTOR");

        assertThatThrownBy(() -> service.settleFromHold("HOLD-B", request("970000.00", "0", "TRD-3")))
                .isInstanceOf(BusinessException.class);
        verify(ledgerPostingService, never()).post(any());
    }

    @Test
    @DisplayName("Mã lệnh không khớp khoản giữ thì từ chối")
    void orderReferenceMustMatch() {
        assertThatThrownBy(() -> service.settleFromHold("HOLD-B",
                new SettleHoldRequest("OB-OTHER", "SELLER", new BigDecimal("1.00"), BigDecimal.ZERO, "TRD-4")))
                .isInstanceOf(BusinessException.class);
        verify(ledgerPostingService, never()).post(any());
    }

    @Test
    @DisplayName("Nhả sau khi đã thanh toán một phần chỉ trả về phần còn lại")
    void releaseReturnsOnlyRemainder() {
        ledgerReturns(false);
        service.settleFromHold("HOLD-B", request("970000.00", "48500.00", "TRD-1"));

        service.release("HOLD-B", "OB-1");

        ArgumentCaptor<LedgerPostingCommand> posted = ArgumentCaptor.forClass(LedgerPostingCommand.class);
        verify(ledgerPostingService, org.mockito.Mockito.times(2)).post(posted.capture());
        LedgerPostingCommand release = posted.getAllValues().get(1);
        assertThat(release.idempotencyKey()).isEqualTo("RELEASE:HOLD-B");
        assertThat(release.entries()).allSatisfy(e -> assertThat(e.amount()).isEqualByComparingTo("1940000.00"));
        assertThat(hold.getStatus()).isEqualTo(PaymentHoldStatus.RELEASED);
    }

    @Test
    @DisplayName("Khoản giữ đã thanh toán hết thì nhả không ghi bút toán số 0")
    void releaseOfFullySettledHoldPostsNothing() {
        ledgerReturns(false);
        service.settleFromHold("HOLD-B", request("2910000.00", "145500.00", "TRD-1"));

        service.release("HOLD-B", "OB-1");

        verify(ledgerPostingService, org.mockito.Mockito.times(1)).post(any());
        assertThat(hold.getStatus()).isEqualTo(PaymentHoldStatus.RELEASED);
    }

    @Test
    @DisplayName("Khoản đã thanh toán một phần không bị capture giải ngân toàn bộ")
    void partiallySettledHoldCannotBeCaptured() {
        hold.settle(new BigDecimal("1.00"), NOW);

        assertThatThrownBy(() -> hold.reserveCapture("SAGA-1", NOW)).isInstanceOf(IllegalStateException.class);
    }
}
