package com.finora.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.common.exception.BusinessException;
import com.finora.payment.domain.ledger.LedgerBalanceBucket;
import com.finora.payment.domain.ledger.LedgerDirection;
import com.finora.payment.domain.ledger.LedgerTransactionStatus;
import com.finora.payment.domain.ledger.LedgerTransactionType;
import com.finora.payment.domain.wallet.PaymentWallet;
import com.finora.payment.domain.wallet.WalletOwnerType;
import com.finora.payment.domain.disbursement.PaymentDisbursement;
import com.finora.payment.domain.disbursement.PaymentDisbursementStatus;
import com.finora.payment.domain.repayment.PaymentRepayment;
import com.finora.payment.domain.repayment.PaymentRepaymentStatus;
import com.finora.payment.dto.request.CreateHoldRequest;
import com.finora.payment.dto.request.SettleHoldRequest;
import com.finora.payment.dto.response.HoldResponse;
import com.finora.payment.dto.response.TopUpResponse;
import com.finora.payment.messaging.DisbursementRequestedEventData;
import com.finora.payment.messaging.LoanDisbursedEventData;
import com.finora.payment.messaging.NoteOwnershipChangedEventData;
import com.finora.payment.repository.PaymentDisbursementRepository;
import com.finora.payment.repository.PaymentOutboxEventRepository;
import com.finora.payment.repository.repayment.PaymentRepaymentRepository;
import com.finora.payment.repository.servicing.PaymentNoteOwnershipRepository;
import com.finora.payment.integration.fineract.RepaymentCoreGateway;
import com.finora.payment.integration.provider.PaymentProvider;
import com.finora.payment.integration.fineract.PartialPrepaymentCoreSnapshot;
import com.finora.payment.integration.fineract.ScheduledRepaymentCoreQuote;
import com.finora.payment.service.disbursement.PaymentDisbursementService;
import com.finora.payment.service.hold.HoldTransferService;
import com.finora.payment.service.topup.TopUpService;
import com.finora.payment.service.repayment.EarlySettlementQuoteService;
import com.finora.payment.service.repayment.PaymentRepaymentService;
import com.finora.payment.service.repayment.PartialPrepaymentQuoteService;
import com.finora.payment.integration.fineract.EarlySettlementCoreQuote;
import com.finora.payment.integration.fineract.RepaymentCoreCommand;
import com.finora.payment.service.servicing.PaymentServicingProjectionService;
import com.finora.payment.repository.ledger.LedgerEntryRepository;
import com.finora.payment.repository.ledger.LedgerTransactionRepository;
import com.finora.payment.repository.wallet.PaymentWalletRepository;
import com.finora.payment.service.ledger.LedgerPostingCommand;
import com.finora.payment.service.ledger.LedgerPostingEntryCommand;
import com.finora.payment.service.ledger.LedgerPostingResult;
import com.finora.payment.service.ledger.LedgerPostingService;
import com.finora.payment.service.wallet.WalletAccountService;
import com.finora.payment.service.wallet.WalletView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Xác nhận Payment khởi động với đúng PostgreSQL 17 và Flyway không còn migration chờ chạy.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "finora.payment.scheduling-enabled=false"
})
@Testcontainers
class FinoraPaymentApplicationIT {

    private static final DockerImageName POSTGRESQL_17 = DockerImageName.parse("postgres:17.5-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(POSTGRESQL_17)
            .withDatabaseName("finora_payment_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PaymentWalletRepository walletRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private WalletAccountService walletService;

    @Autowired
    private LedgerPostingService postingService;

    @Autowired
    private TopUpService topUpService;

    @Autowired
    private HoldTransferService holdTransferService;

    @Autowired
    private PaymentDisbursementService disbursementService;

    @Autowired
    private PaymentDisbursementRepository disbursementRepository;

    @Autowired
    private PaymentServicingProjectionService servicingProjectionService;

    @Autowired
    private PaymentRepaymentService repaymentService;

    @Autowired
    private PartialPrepaymentQuoteService partialPrepaymentQuoteService;

    @Autowired
    private PaymentRepaymentRepository repaymentRepository;

    @Autowired
    private PaymentNoteOwnershipRepository noteOwnershipRepository;

    @Autowired
    private PaymentOutboxEventRepository outboxRepository;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private com.finora.payment.repository.servicing.PaymentLoanAccountRepository loanAccountRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private MutableTestClock testClock;

    @Autowired
    private EarlySettlementQuoteService earlySettlementQuoteService;

    @MockBean
    private RepaymentCoreGateway repaymentCoreGateway;

    /** Đồng hồ chung của mọi bean Payment; test dời tới ngày đến hạn để kiểm khoản trả kỳ được giữ (D16). */
    @TestConfiguration
    static class TestClockConfiguration {
        @Bean
        @Primary
        MutableTestClock testClock() {
            return new MutableTestClock(ZoneId.of("Asia/Ho_Chi_Minh"));
        }
    }

    @BeforeEach
    void cleanLedger() {
        testClock.reset();
        jdbcTemplate.execute("""
                TRUNCATE TABLE payment_repayments, payment_partial_prepayment_quotes, payment_early_settlement_quotes,
                    payment_note_ownership, payment_loan_accounts,
                    payment_top_ups, payment_holds, payment_disbursements,
                    payment_processed_events, payment_outbox_events,
                    payment_ledger_entries, payment_ledger_transactions, payment_wallets
                RESTART IDENTITY CASCADE
                """);
        SecurityContextHolder.clearContext();
    }

    @Test
    void contextUsesPostgreSql17AndHasNoPendingMigration() {
        String version = jdbcTemplate.queryForObject("SHOW server_version", String.class);

        assertThat(version).startsWith("17.");
        assertThat(flyway.info().pending()).isEmpty();
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_name IN ('payment_wallets', 'payment_ledger_transactions', 'payment_ledger_entries')
                """, Integer.class);
        assertThat(tableCount).isEqualTo(3);
    }

    @Test
    void balancedPostingIsAtomicAndIdempotent() {
        WalletView wallet = walletService.open(WalletOwnerType.INVESTOR, "INV-001", "VND");
        assertThat(walletService.open(WalletOwnerType.INVESTOR, "INV-001", "VND").walletId())
                .isEqualTo(wallet.walletId());
        LedgerPostingCommand command = deposit("deposit-001", "DEP-001", wallet.walletId(), "100.00");

        LedgerPostingResult first = postingService.post(command);
        LedgerPostingResult replay = postingService.post(command);

        PaymentWallet stored = walletRepository.findByWalletId(wallet.walletId()).orElseThrow();
        assertThat(first.status()).isEqualTo(LedgerTransactionStatus.POSTED);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transactionId()).isEqualTo(first.transactionId());
        assertThat(stored.getAvailableBalance()).isEqualByComparingTo("100.00");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(entryRepository.count()).isEqualTo(2);

        assertThatThrownBy(() -> postingService.post(deposit(
                "deposit-001", "DEP-001", wallet.walletId(), "120.00")))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("PAYMENT_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void unbalancedPostingRollsBackBeforeCreatingTransaction() {
        WalletView wallet = walletService.open(WalletOwnerType.INVESTOR, "INV-002", "VND");
        LedgerPostingCommand command = new LedgerPostingCommand(
                "unbalanced-001", LedgerTransactionType.ADJUSTMENT, "TEST", "UNBALANCED-001", "VND",
                List.of(
                        clearing(LedgerDirection.DEBIT, "100.00"),
                        wallet(wallet.walletId(), LedgerDirection.CREDIT, "99.00")));

        assertThatThrownBy(() -> postingService.post(command))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("PAYMENT_LEDGER_UNBALANCED");
        assertThat(transactionRepository.count()).isZero();
        assertThat(walletRepository.findByWalletId(wallet.walletId()).orElseThrow().getAvailableBalance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void concurrentDebitsCannotMakeWalletNegative() throws Exception {
        WalletView wallet = walletService.open(WalletOwnerType.INVESTOR, "INV-003", "VND");
        postingService.post(deposit("seed-001", "SEED-001", wallet.walletId(), "100.00"));
        LedgerPostingCommand first = withdrawal("withdraw-001", "WD-001", wallet.walletId(), "80.00");
        LedgerPostingCommand second = withdrawal("withdraw-002", "WD-002", wallet.walletId(), "80.00");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> firstResult = executor.submit(() -> postAfterBarrier(first, ready, start));
            Future<Boolean> secondResult = executor.submit(() -> postAfterBarrier(second, ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(firstResult.get(), secondResult.get())).containsExactlyInAnyOrder(true, false);
        }

        PaymentWallet stored = walletRepository.findByWalletId(wallet.walletId()).orElseThrow();
        assertThat(stored.getAvailableBalance()).isEqualByComparingTo("20.00");
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(entryRepository.count()).isEqualTo(4);
    }

    @Test
    void postedLedgerRowsAreImmutableAtDatabaseLevel() {
        WalletView wallet = walletService.open(WalletOwnerType.INVESTOR, "INV-004", "VND");
        postingService.post(deposit("deposit-immutable", "DEP-IMMUTABLE", wallet.walletId(), "10.00"));

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE payment_ledger_entries SET amount = 11"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("payment ledger entries are append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM payment_ledger_transactions"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("payment ledger transactions cannot be deleted");
    }

    @Test
    void topUpHoldAndDisbursementCaptureUseOneBalancedLedger() {
        actAs("INVESTOR-100");
        TopUpResponse topUp = topUpService.create(new BigDecimal("5000000"), "topup-it-100");
        topUpService.completeMock(topUp.topUpId());

        HoldResponse hold = holdTransferService.hold(new CreateHoldRequest(
                "INVESTOR-100", new BigDecimal("2000000.00"), "ORDER-100"));
        UUID sagaId = UUID.randomUUID();
        disbursementService.request(UUID.randomUUID(), new DisbursementRequestedEventData(
                sagaId, 100L, "LA-100", "LC-100", 200L, "BORROWER-100",
                "2000000.00", "VND", List.of(new DisbursementRequestedEventData.Allocation(
                300L, "INVESTOR-100", "2000000.00", hold.holdReference()))));

        Long workId = disbursementService.dueIds().getFirst();
        disbursementService.execute(workId);

        PaymentWallet wallet = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.INVESTOR, "INVESTOR-100", "VND").orElseThrow();
        PaymentWallet borrowerWallet = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.BORROWER, "BORROWER-100", "VND").orElseThrow();
        assertThat(wallet.getAvailableBalance()).isEqualByComparingTo("3000000.00");
        assertThat(wallet.getHeldBalance()).isEqualByComparingTo("0.00");
        assertThat(borrowerWallet.getAvailableBalance()).isEqualByComparingTo("2000000.00");
        assertThat(borrowerWallet.getHeldBalance()).isEqualByComparingTo("0.00");
        assertThat(disbursementRepository.findBySagaId(sagaId).orElseThrow().getStatus())
                .isEqualTo(PaymentDisbursementStatus.COMPLETED);
    }

    /**
     * Sổ lệnh Notes: một lệnh mua giữ tiền một lần, khớp hai lần với người bán, rồi nhả phần thừa.
     * Gọi lại cùng mã thanh toán không chuyển tiền lần hai; ví cuối cùng khớp từng đồng.
     */
    @Test
    void holdIsSettledInPartsThenRemainderReleased() {
        actAs("BUYER-OB");
        TopUpResponse topUp = topUpService.create(new BigDecimal("5000000"), "topup-it-ob");
        topUpService.completeMock(topUp.topUpId());
        HoldResponse hold = holdTransferService.hold(new CreateHoldRequest(
                "BUYER-OB", new BigDecimal("2910000.00"), "OB-IT-1"));

        actAsInvestmentService();
        SettleHoldRequest first = new SettleHoldRequest(
                "OB-IT-1", "SELLER-OB", new BigDecimal("970000.00"), new BigDecimal("48500.00"), "TRD-IT-1");
        holdTransferService.settleFromHold(hold.holdReference(), first);
        assertThat(holdTransferService.settleFromHold(hold.holdReference(), first).replayed()).isTrue();
        holdTransferService.settleFromHold(hold.holdReference(), new SettleHoldRequest(
                "OB-IT-1", "SELLER-OB", new BigDecimal("1000000.00"), new BigDecimal("50000.00"), "TRD-IT-2"));
        holdTransferService.release(hold.holdReference(), "OB-IT-1");

        PaymentWallet buyer = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.INVESTOR, "BUYER-OB", "VND").orElseThrow();
        PaymentWallet seller = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.INVESTOR, "SELLER-OB", "VND").orElseThrow();
        // Người mua: 5tr − 970.000 − 1.000.000; 940.000 giữ thừa đã về ví.
        assertThat(buyer.getAvailableBalance()).isEqualByComparingTo("3030000.00");
        assertThat(buyer.getHeldBalance()).isEqualByComparingTo("0.00");
        // Người bán: 921.500 + 950.000 sau phí 5%.
        assertThat(seller.getAvailableBalance()).isEqualByComparingTo("1871500.00");
    }

    /**
     * Worker chết sau khi đã giữ chỗ capture và gọi provider: hết lease thì không gọi chuyển tiền lại mà tra cứu
     * provider theo sagaId, rồi hoàn tất đúng một lần. Kết quả lần gọi cũ đến muộn sau đó bị bỏ qua.
     */
    @Test
    void interruptedDisbursementIsReconciledByLookupAndCompletedExactlyOnce() {
        actAs("INVESTOR-LEASE");
        TopUpResponse topUp = topUpService.create(new BigDecimal("5000000"), "topup-it-lease");
        topUpService.completeMock(topUp.topUpId());
        HoldResponse hold = holdTransferService.hold(new CreateHoldRequest(
                "INVESTOR-LEASE", new BigDecimal("2000000.00"), "ORDER-LEASE"));
        UUID sagaId = UUID.randomUUID();
        disbursementService.request(UUID.randomUUID(), new DisbursementRequestedEventData(
                sagaId, 110L, "LA-110", "LC-110", 210L, "BORROWER-LEASE",
                "2000000.00", "VND", List.of(new DisbursementRequestedEventData.Allocation(
                310L, "INVESTOR-LEASE", "2000000.00", hold.holdReference()))));
        Long id = disbursementRepository.findBySagaId(sagaId).orElseThrow().getId();

        // Mô phỏng worker chết ngay sau khi claim: lệnh còn PROCESSING, hold đang CAPTURE_PENDING.
        assertThat(disbursementService.claim(id)).isNotNull();
        assertThat(disbursementService.dueIds()).doesNotContain(id);
        jdbcTemplate.update("UPDATE payment_disbursements SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);

        assertThat(disbursementService.dueIds()).contains(id);
        disbursementService.execute(id);

        PaymentDisbursement reconciled = disbursementRepository.findById(id).orElseThrow();
        assertThat(reconciled.getStatus()).isEqualTo(PaymentDisbursementStatus.COMPLETED);
        assertThat(reconciled.getAttemptCount()).isEqualTo(1);
        assertThat(reconciled.getProviderReference()).isEqualTo("MOCK-" + sagaId);
        PaymentWallet borrowerWallet = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.BORROWER, "BORROWER-LEASE", "VND").orElseThrow();
        assertThat(borrowerWallet.getAvailableBalance()).isEqualByComparingTo("2000000.00");

        // Kết quả của lần gọi provider cũ đến muộn: không capture, không ghi có hay phát event lần hai.
        long ledgerTransactions = transactionRepository.count();
        disbursementService.finish(id, PaymentProvider.PaymentProviderResult.success("MOCK-" + sagaId));
        assertThat(transactionRepository.count()).isEqualTo(ledgerTransactions);
        assertThat(outboxRepository.findAll()).filteredOn(event -> "DisbursementCompleted".equals(event.getEventType()))
                .hasSize(1);
        assertThat(walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.BORROWER, "BORROWER-LEASE", "VND").orElseThrow().getAvailableBalance())
                .isEqualByComparingTo("2000000.00");
    }

    @Test
    void uncertainProviderResultWaitsForLookupInsteadOfRetryingTransfer() {
        actAs("INVESTOR-UNCERTAIN");
        TopUpResponse topUp = topUpService.create(new BigDecimal("3000000"), "topup-it-uncertain");
        topUpService.completeMock(topUp.topUpId());
        HoldResponse hold = holdTransferService.hold(new CreateHoldRequest(
                "INVESTOR-UNCERTAIN", new BigDecimal("1000000.00"), "ORDER-UNCERTAIN"));
        UUID sagaId = UUID.randomUUID();
        disbursementService.request(UUID.randomUUID(), new DisbursementRequestedEventData(
                sagaId, 111L, "LA-111", "LC-111", 211L, "BORROWER-UNCERTAIN",
                "1000000.00", "VND", List.of(new DisbursementRequestedEventData.Allocation(
                311L, "INVESTOR-UNCERTAIN", "1000000.00", hold.holdReference()))));
        Long id = disbursementRepository.findBySagaId(sagaId).orElseThrow().getId();
        assertThat(disbursementService.claim(id)).isNotNull();

        disbursementService.finish(id, PaymentProvider.PaymentProviderResult.failure(
                "PAYMENT_PROVIDER_UNCERTAIN", "timeout", false));

        PaymentDisbursement pending = disbursementRepository.findById(id).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(PaymentDisbursementStatus.RECONCILIATION_REQUIRED);
        assertThat(pending.getNextAttemptAt()).isNotNull();
        assertThat(outboxRepository.findAll()).filteredOn(event -> "DisbursementFailed".equals(event.getEventType()))
                .isEmpty();

        jdbcTemplate.update("UPDATE payment_disbursements SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
        disbursementService.execute(id);

        assertThat(disbursementRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(PaymentDisbursementStatus.COMPLETED);
        assertThat(disbursementRepository.findById(id).orElseThrow().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void completedLegacyDisbursementCreditsBorrowerExactlyOnce() {
        UUID sagaId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-28T12:00:00Z");
        PaymentDisbursement legacy = PaymentDisbursement.requested(
                sagaId, 102L, "LC-LEGACY", 202L, "BORROWER-LEGACY",
                new BigDecimal("7000000.00"), "VND", "MOCK", "[]", now);
        legacy.start(now, now.plusSeconds(120));
        legacy.complete("MOCK-LEGACY-" + sagaId, UUID.randomUUID(), now);
        legacy = disbursementRepository.saveAndFlush(legacy);

        assertThat(disbursementService.completedAwaitingBorrowerCreditIds()).contains(legacy.getId());

        disbursementService.reconcileBorrowerCredit(legacy.getId());
        disbursementService.reconcileBorrowerCredit(legacy.getId());

        PaymentWallet borrowerWallet = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.BORROWER, "BORROWER-LEGACY", "VND").orElseThrow();
        assertThat(borrowerWallet.getAvailableBalance()).isEqualByComparingTo("7000000.00");
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(entryRepository.count()).isEqualTo(2);
        assertThat(disbursementService.completedAwaitingBorrowerCreditIds()).doesNotContain(legacy.getId());
    }

    @Test
    void duplicateDisbursementSagaRejectsDifferentFinancialPayload() {
        UUID sagaId = UUID.randomUUID();
        DisbursementRequestedEventData original = new DisbursementRequestedEventData(
                sagaId, 101L, "LA-101", "LC-101", 201L, "BORROWER-101",
                "1000000.00", "VND", List.of(new DisbursementRequestedEventData.Allocation(
                301L, "INVESTOR-101", "1000000.00", "HOLD-101")));
        DisbursementRequestedEventData conflicting = new DisbursementRequestedEventData(
                sagaId, 101L, "LA-101", "LC-101", 201L, "BORROWER-101",
                "1500000.00", "VND", List.of(new DisbursementRequestedEventData.Allocation(
                301L, "INVESTOR-101", "1500000.00", "HOLD-101")));

        disbursementService.request(UUID.randomUUID(), original);

        assertThatThrownBy(() -> disbursementService.request(UUID.randomUUID(), conflicting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sagaId");
        assertThat(disbursementRepository.count()).isEqualTo(1);
        assertThat(disbursementRepository.findBySagaId(sagaId).orElseThrow().getAmount())
                .isEqualByComparingTo("1000000.00");
    }

    @Test
    void scheduledDueReturnsCoreObligationOnlyToLoanOwner() {
        long applicationId = 521L;
        long fineractLoanId = 721L;
        UUID sagaId = UUID.randomUUID();
        Instant disbursedAt = Instant.parse("2026-10-01T00:00:00Z");
        PaymentDisbursement disbursement = PaymentDisbursement.requested(
                sagaId, applicationId, "LC-521", 621L, "BORROWER-521",
                new BigDecimal("10000000.00"), "VND", "MOCK", "[]", disbursedAt);
        disbursement.start(disbursedAt, disbursedAt.plusSeconds(120));
        disbursement.complete("MOCK-521", UUID.randomUUID(), disbursedAt);
        disbursementRepository.saveAndFlush(disbursement);
        servicingProjectionService.activateLoan(UUID.randomUUID(), new LoanDisbursedEventData(
                sagaId, applicationId, "LA-521", "LC-521", 621L, "10000000.00", "VND",
                "MOCK-521", fineractLoanId, "FINORA-FINERACT-V3", disbursedAt));
        // Fineract trả số tiền scale 6 và đã làm tròn bội 1.000 VND; API phải trả đúng giá trị, tối đa 2 chữ số.
        when(repaymentCoreGateway.scheduledRepaymentQuote(fineractLoanId))
                .thenReturn(new ScheduledRepaymentCoreQuote(
                        new BigDecimal("1725000.000000"), new BigDecimal("125000.000000")));

        actAsBorrower("BORROWER-521");
        var due = repaymentService.scheduledDue(applicationId);
        assertThat(due.amount()).isEqualByComparingTo("1725000.00");
        assertThat(due.amount().scale()).isEqualTo(2);
        assertThat(due.overdueAmount()).isEqualByComparingTo("125000.00");
        assertThat(due.repaymentType()).isEqualTo(com.finora.payment.domain.repayment.PaymentRepaymentType.OVERDUE_CURE);
        assertThat(due.businessDate()).isEqualTo(LocalDate.now(clock));

        actAsBorrower("BORROWER-OTHER");
        assertThatThrownBy(() -> repaymentService.scheduledDue(applicationId))
                .isInstanceOfSatisfying(com.finora.common.exception.BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PAYMENT_LOAN_OWNER_MISMATCH"));

        actAsBorrower("BORROWER-521");
        when(repaymentCoreGateway.scheduledRepaymentQuote(fineractLoanId))
                .thenThrow(new com.finora.payment.integration.fineract.FineractRepaymentException(
                        "FINERACT_DUE_UNAVAILABLE", "timeout", true, null));
        assertThatThrownBy(() -> repaymentService.scheduledDue(applicationId))
                .isInstanceOfSatisfying(com.finora.common.exception.BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getCode()).isEqualTo("FINERACT_REPAYMENT_QUOTE_UNAVAILABLE");
                });
    }

    @Test
    void scheduledRepaymentMovesMoneyThroughCoreAndDistributesToCurrentNoteOwner() {
        long applicationId = 501L;
        long listingId = 601L;
        long fineractLoanId = 701L;
        UUID sagaId = UUID.randomUUID();
        Instant disbursedAt = Instant.parse("2026-10-01T00:00:00Z");
        PaymentDisbursement disbursement = PaymentDisbursement.requested(
                sagaId, applicationId, "LC-501", listingId, "BORROWER-501",
                new BigDecimal("10000000.00"), "VND", "MOCK", "[]", disbursedAt);
        disbursement.start(disbursedAt, disbursedAt.plusSeconds(120));
        disbursement.complete("MOCK-501", UUID.randomUUID(), disbursedAt);
        disbursementRepository.saveAndFlush(disbursement);
        servicingProjectionService.activateLoan(UUID.randomUUID(), new LoanDisbursedEventData(
                sagaId, applicationId, "LA-501", "LC-501", listingId, "10000000.00", "VND",
                "MOCK-501", fineractLoanId, "FINORA-FINERACT-V2", disbursedAt));
        servicingProjectionService.updateOwnership(UUID.randomUUID(), new NoteOwnershipChangedEventData(
                applicationId, listingId, "VND", "ISSUED", "NOTE-ISSUE-501", disbursedAt,
                List.of(new NoteOwnershipChangedEventData.NoteOwner(
                        801L, "NOTE-501", "INVESTOR-501", "10000000.00"))));

        actAsBorrower("BORROWER-501");
        TopUpResponse topUp = topUpService.create(new BigDecimal("4500000"), "topup-repay-501");
        topUpService.completeMock(topUp.topUpId());
        when(repaymentCoreGateway.scheduledRepaymentQuote(fineractLoanId))
                .thenReturn(new ScheduledRepaymentCoreQuote(
                        new BigDecimal("4500000.00"), BigDecimal.ZERO.setScale(2)));
        when(repaymentCoreGateway.postRepayment(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PaymentRepayment.CoreBreakdown(
                        901L, new BigDecimal("4000000.00"), new BigDecimal("500000.00"),
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        new BigDecimal("6000000.00"), BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        new BigDecimal("6000000.00"), BigDecimal.ZERO.setScale(2),
                        LocalDate.now(clock).plusMonths(1), new BigDecimal("4500000.00")));

        var collected = repaymentService.create(applicationId, new BigDecimal("4500000.00"),
                LocalDate.now(clock), "repay-it-501");
        PaymentRepayment repayment = repaymentRepository.findByRepaymentId(collected.repaymentId()).orElseThrow();
        repaymentService.postToCore(repayment.getId());
        repaymentService.distribute(repayment.getId());

        PaymentRepayment completed = repaymentRepository.findByRepaymentId(collected.repaymentId()).orElseThrow();
        PaymentWallet borrower = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.BORROWER, "BORROWER-501", "VND").orElseThrow();
        PaymentWallet investor = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.INVESTOR, "INVESTOR-501", "VND").orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PaymentRepaymentStatus.COMPLETED);
        assertThat(borrower.getAvailableBalance()).isEqualByComparingTo("0.00");
        assertThat(investor.getAvailableBalance()).isEqualByComparingTo("4500000.00");
        assertThat(noteOwnershipRepository.findById(801L).orElseThrow().getOutstandingPrincipal())
                .isEqualByComparingTo("6000000.00");
        assertThat(outboxRepository.findAll()).anySatisfy(event ->
                assertThat(event.getEventType()).isEqualTo("RepaymentDistributed"));
    }

    @Test
    void partialPrepaymentQuotesCollectsPostsAndDistributesWithDailyInterestCoreAccount() {
        long applicationId = 502L;
        long listingId = 602L;
        long fineractLoanId = 702L;
        UUID sagaId = UUID.randomUUID();
        Instant disbursedAt = Instant.parse("2026-10-01T00:00:00Z");
        PaymentDisbursement disbursement = PaymentDisbursement.requested(
                sagaId, applicationId, "LC-502", listingId, "BORROWER-502",
                new BigDecimal("10000000.00"), "VND", "MOCK", "[]", disbursedAt);
        disbursement.start(disbursedAt, disbursedAt.plusSeconds(120));
        disbursement.complete("MOCK-502", UUID.randomUUID(), disbursedAt);
        disbursementRepository.saveAndFlush(disbursement);
        // Hợp đồng công bố biểu phí AGRIBANK V2; LoanDisbursed mang phiên bản đó sang Payment. Chỉ core V4 (lãi theo
        // dư nợ và số ngày thực tế) được trả trước một phần tự phục vụ (D17).
        servicingProjectionService.activateLoan(UUID.randomUUID(), new LoanDisbursedEventData(
                sagaId, applicationId, "LA-502", "LC-502", listingId, "10000000.00", "VND",
                "MOCK-502", fineractLoanId, "FINORA-FINERACT-V4", disbursedAt, "AGRIBANK_REFERENCE_FINORA_2026_V2"));
        servicingProjectionService.updateOwnership(UUID.randomUUID(), new NoteOwnershipChangedEventData(
                applicationId, listingId, "VND", "ISSUED", "NOTE-ISSUE-502", disbursedAt,
                List.of(new NoteOwnershipChangedEventData.NoteOwner(
                        802L, "NOTE-502", "INVESTOR-502", "10000000.00"))));

        actAsBorrower("BORROWER-502");
        TopUpResponse topUp = topUpService.create(new BigDecimal("5100000"), "topup-partial-502");
        topUpService.completeMock(topUp.topUpId());
        LocalDate today = LocalDate.now(clock);
        when(repaymentCoreGateway.partialPrepaymentSnapshot(fineractLoanId, today))
                .thenReturn(new PartialPrepaymentCoreSnapshot(today, BigDecimal.ZERO.setScale(2),
                        new BigDecimal("10500000.00"), new BigDecimal("10000000.00"),
                        today.plusMonths(1), new BigDecimal("1800000.00"), 12,
                        today.minusMonths(1), today.plusMonths(11)));
        when(repaymentCoreGateway.postRepayment(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PaymentRepayment.CoreBreakdown(
                        902L, new BigDecimal("5000000.00"), BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        new BigDecimal("5000000.00"), BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        new BigDecimal("5000000.00"), BigDecimal.ZERO.setScale(2),
                        today.plusMonths(1), new BigDecimal("950000.00")));

        var quote = partialPrepaymentQuoteService.create(applicationId, new BigDecimal("5000000.00"));
        assertThat(quote.coreAmount()).isEqualByComparingTo("5000000.00");
        assertThat(quote.platformFee()).isEqualByComparingTo("100000.00");
        assertThat(quote.investorFeeShare()).isEqualByComparingTo("80000.00");
        assertThat(quote.finoraFeeShare()).isEqualByComparingTo("20000.00");
        var collected = repaymentService.createPartialPrepayment(quote.quoteId(), "partial-it-502");
        PaymentRepayment repayment = repaymentRepository.findByRepaymentId(collected.repaymentId()).orElseThrow();
        repaymentService.postToCore(repayment.getId());
        repaymentService.distribute(repayment.getId());

        PaymentRepayment completed = repaymentRepository.findByRepaymentId(collected.repaymentId()).orElseThrow();
        PaymentWallet borrower = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.BORROWER, "BORROWER-502", "VND").orElseThrow();
        PaymentWallet investor = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.INVESTOR, "INVESTOR-502", "VND").orElseThrow();
        PaymentWallet platform = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.PLATFORM, "FINORA", "VND").orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PaymentRepaymentStatus.COMPLETED);
        assertThat(completed.getCoreAmount()).isEqualByComparingTo("5000000.00");
        assertThat(completed.getPlatformFee()).isEqualByComparingTo("100000.00");
        assertThat(completed.getInvestorFeeShare()).isEqualByComparingTo("80000.00");
        assertThat(completed.getFinoraFeeShare()).isEqualByComparingTo("20000.00");
        assertThat(borrower.getAvailableBalance()).isEqualByComparingTo("0.00");
        assertThat(investor.getAvailableBalance()).isEqualByComparingTo("5080000.00");
        assertThat(platform.getAvailableBalance()).isEqualByComparingTo("20000.00");
        assertThat(noteOwnershipRepository.findById(802L).orElseThrow().getOutstandingPrincipal())
                .isEqualByComparingTo("5000000.00");
    }

    @Test
    void prepaymentFeeFollowsTheContractPolicyNotTheCurrentOne() {
        LocalDate today = LocalDate.now(clock);
        long disclosed = activateLoanForQuote(503L, 603L, 703L, "AGRIBANK_REFERENCE_FINORA_2026_V2");
        long legacy = activateLoanForQuote(504L, 604L, 704L, null);
        for (long fineractLoanId : List.of(disclosed, legacy)) {
            when(repaymentCoreGateway.partialPrepaymentSnapshot(fineractLoanId, today))
                    .thenReturn(new PartialPrepaymentCoreSnapshot(today, BigDecimal.ZERO.setScale(2),
                            new BigDecimal("10500000.00"), new BigDecimal("10000000.00"),
                            today.plusMonths(1), new BigDecimal("1800000.00"), 12,
                            today.minusMonths(1), today.plusMonths(11)));
        }

        actAsBorrower("BORROWER-503");
        var v2Quote = partialPrepaymentQuoteService.create(503L, new BigDecimal("5000000.00"));
        actAsBorrower("BORROWER-504");
        var legacyQuote = partialPrepaymentQuoteService.create(504L, new BigDecimal("5000000.00"));

        // Cùng ngày, cùng số tiền: hợp đồng V2 chịu phí theo biểu phí đã ký, hợp đồng không ghi biểu phí thì không bị thu.
        assertThat(v2Quote.policyVersion()).isEqualTo("AGRIBANK_REFERENCE_FINORA_2026_V2");
        assertThat(v2Quote.platformFee()).isEqualByComparingTo("100000.00");
        assertThat(legacyQuote.policyVersion()).isEqualTo("NO_FEE_DISCLOSED");
        assertThat(legacyQuote.platformFee()).isEqualByComparingTo("0.00");
        assertThat(legacyQuote.investorFeeShare()).isEqualByComparingTo("0.00");
        assertThat(loanAccountRepository.findByLoanApplicationId(503L).orElseThrow().getEarlySettlementPolicyVersion())
                .isEqualTo("AGRIBANK_REFERENCE_FINORA_2026_V2");
        assertThat(loanAccountRepository.findByLoanApplicationId(504L).orElseThrow().getEarlySettlementPolicyVersion())
                .isNull();
    }

    @Test
    void earlyInstallmentOnDailyInterestLoanIsHeldUntilItsDueDateThenPostedWithThatDate() {
        long applicationId = 531L;
        long fineractLoanId = 731L;
        activateLoan(applicationId, 631L, fineractLoanId, "FINORA-FINERACT-V4", "AGRIBANK_REFERENCE_FINORA_2026_V2");
        issueNote(applicationId, 631L, 831L, "INVESTOR-531");
        actAsBorrower("BORROWER-531");
        fundBorrower("1725000", "topup-early-531");
        LocalDate today = LocalDate.now(clock);
        LocalDate dueDate = today.plusDays(16);
        when(repaymentCoreGateway.scheduledRepaymentQuote(fineractLoanId)).thenReturn(new ScheduledRepaymentCoreQuote(
                new BigDecimal("1725000.00"), BigDecimal.ZERO.setScale(2), dueDate));

        var due = repaymentService.scheduledDue(applicationId);
        assertThat(due.dueDate()).isEqualTo(dueDate);
        assertThat(due.postOn()).isEqualTo(dueDate);
        assertThat(due.pending()).isNull();

        var held = repaymentService.create(applicationId, new BigDecimal("1725000.00"), today, "early-531");
        assertThat(held.status()).isEqualTo(PaymentRepaymentStatus.COLLECTED);
        assertThat(held.transactionDate()).isEqualTo(today);
        assertThat(held.postOn()).isEqualTo(dueDate);
        assertThat(borrowerAvailable("BORROWER-531")).isEqualByComparingTo("0.00");
        // P5 (D77): cùng transaction với bút toán trừ ví, Payment báo khoản giữ để Notification không nhắc kỳ này.
        assertThat(outboxEvents("RepaymentCollected")).singleElement().satisfies(event -> {
            assertThat(event.getAggregateId()).isEqualTo(Long.toString(applicationId));
            assertThat(event.getEventVersion()).isEqualTo(1);
            JsonNode data = json(event.getPayloadJson());
            assertThat(data.path("repaymentId").asText()).isEqualTo(held.repaymentId().toString());
            assertThat(data.path("loanApplicationId").asLong()).isEqualTo(applicationId);
            assertThat(data.path("borrowerId").asText()).isEqualTo("BORROWER-531");
            assertThat(data.path("repaymentType").asText()).isEqualTo("SCHEDULED");
            assertThat(data.path("amount").isTextual()).isTrue();
            assertThat(data.path("amount").asText()).isEqualTo("1725000.00");
            assertThat(data.path("currency").asText()).isEqualTo("VND");
            assertThat(data.path("dueDate").asText()).isEqualTo(dueDate.toString());
            assertThat(data.path("collectedAt").isTextual()).isTrue();
        });
        Long id = repaymentRepository.findByRepaymentId(held.repaymentId()).orElseThrow().getId();
        // D16: chưa tới ngày đến hạn thì worker không thấy, gọi trực tiếp cũng không ghi core.
        assertThat(repaymentService.collectedIds()).doesNotContain(id);
        repaymentService.postToCore(id);
        verify(repaymentCoreGateway, never()).postRepayment(any());
        assertThat(repaymentService.scheduledDue(applicationId).pending()).satisfies(pending -> {
            assertThat(pending.repaymentId()).isEqualTo(held.repaymentId());
            assertThat(pending.postOn()).isEqualTo(dueDate);
            assertThat(pending.cancellable()).isTrue();
        });
        // Không trả hai lần cùng một kỳ, không báo giá trên dư nợ chưa gồm khoản đang giữ.
        assertThatThrownBy(() -> repaymentService.create(applicationId, new BigDecimal("1725000.00"), today,
                "early-531-again")).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("INSTALLMENT_ALREADY_PAID_PENDING"));
        assertThatThrownBy(() -> partialPrepaymentQuoteService.create(applicationId, new BigDecimal("3000000.00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("INSTALLMENT_PENDING_POSTING"));
        assertThatThrownBy(() -> earlySettlementQuoteService.create(applicationId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("INSTALLMENT_PENDING_POSTING"));
        // Gửi lại đúng lệnh cũ vẫn trả kết quả cũ, không bị chặn, và không phát event giữ lần hai.
        assertThat(repaymentService.create(applicationId, new BigDecimal("1725000.00"), today, "early-531")
                .repaymentId()).isEqualTo(held.repaymentId());
        assertThat(outboxEvents("RepaymentCollected")).hasSize(1);

        testClock.advance(Duration.ofDays(16));
        when(repaymentCoreGateway.postRepayment(any())).thenReturn(new PaymentRepayment.CoreBreakdown(
                931L, new BigDecimal("1626000.00"), new BigDecimal("99000.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("8374000.00"), BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("8374000.00"), BigDecimal.ZERO.setScale(2),
                dueDate.plusMonths(1), new BigDecimal("1725000.00")));
        assertThat(repaymentService.collectedIds()).contains(id);
        assertThatThrownBy(() -> repaymentService.cancelPending(held.repaymentId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("REPAYMENT_NOT_CANCELLABLE"));
        repaymentService.postToCore(id);
        ArgumentCaptor<RepaymentCoreCommand> command = ArgumentCaptor.forClass(RepaymentCoreCommand.class);
        verify(repaymentCoreGateway).postRepayment(command.capture());
        // Ghi đúng ngày đến hạn nên Fineract giữ nguyên lãi của kỳ theo lịch.
        assertThat(command.getValue().transactionDate()).isEqualTo(dueDate);
        repaymentService.distribute(id);

        PaymentRepayment completed = repaymentRepository.findById(id).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PaymentRepaymentStatus.COMPLETED);
        assertThat(completed.getInterestAmount()).isEqualByComparingTo("99000.00");
        assertThat(walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.INVESTOR, "INVESTOR-531", "VND").orElseThrow().getAvailableBalance())
                .isEqualByComparingTo("1725000.00");
        // P4: người vay nằm trong RepaymentDistributed (ID logic đã lưu trên khoản trả); kỳ tiếp theo vẫn từ Fineract.
        assertThat(outboxEvents("RepaymentDistributed")).singleElement().satisfies(event -> {
            JsonNode data = json(event.getPayloadJson());
            assertThat(data.path("repaymentId").asText()).isEqualTo(held.repaymentId().toString());
            assertThat(data.path("borrowerId").asText()).isEqualTo("BORROWER-531");
            assertThat(data.path("nextDueDate").asText()).isEqualTo(dueDate.plusMonths(1).toString());
            assertThat(data.path("nextDueAmount").asText()).isEqualTo("1725000.00");
        });
        assertThat(outboxEvents("RepaymentCancelled")).isEmpty();
        // Consumer LoanDisbursed của Payment đọc bằng chính ObjectMapper này: field Loan bổ sung (P2/P3) bị bỏ qua.
        assertThat(objectMapper.isEnabled(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
    }

    @Test
    void borrowerCancelsHeldInstallmentForARefundAndCanThenQuoteSettlement() {
        long applicationId = 532L;
        long fineractLoanId = 732L;
        activateLoan(applicationId, 632L, fineractLoanId, "FINORA-FINERACT-V4", "AGRIBANK_REFERENCE_FINORA_2026_V2");
        actAsBorrower("BORROWER-532");
        fundBorrower("1725000", "topup-cancel-532");
        LocalDate today = LocalDate.now(clock);
        when(repaymentCoreGateway.scheduledRepaymentQuote(fineractLoanId)).thenReturn(new ScheduledRepaymentCoreQuote(
                new BigDecimal("1725000.00"), BigDecimal.ZERO.setScale(2), today.plusDays(10)));
        var held = repaymentService.create(applicationId, new BigDecimal("1725000.00"), today, "cancel-532");

        actAsBorrower("BORROWER-OTHER");
        assertThatThrownBy(() -> repaymentService.cancelPending(held.repaymentId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("REPAYMENT_OWNER_MISMATCH"));
        actAsBorrower("BORROWER-532");
        assertThat(outboxEvents("RepaymentCancelled")).isEmpty();
        var cancelled = repaymentService.cancelPending(held.repaymentId());
        assertThat(cancelled.status()).isEqualTo(PaymentRepaymentStatus.CANCELLED);
        assertThat(borrowerAvailable("BORROWER-532")).isEqualByComparingTo("1725000.00");
        // Hủy lại không hoàn tiền lần hai và không phát RepaymentCancelled lần hai (P5, D77).
        assertThat(repaymentService.cancelPending(held.repaymentId()).status())
                .isEqualTo(PaymentRepaymentStatus.CANCELLED);
        assertThat(borrowerAvailable("BORROWER-532")).isEqualByComparingTo("1725000.00");
        assertThat(outboxEvents("RepaymentCollected")).hasSize(1);
        assertThat(outboxEvents("RepaymentCancelled")).singleElement().satisfies(event -> {
            assertThat(event.getAggregateId()).isEqualTo(Long.toString(applicationId));
            JsonNode data = json(event.getPayloadJson());
            assertThat(data.path("repaymentId").asText()).isEqualTo(held.repaymentId().toString());
            assertThat(data.path("loanApplicationId").asLong()).isEqualTo(applicationId);
            assertThat(data.path("borrowerId").asText()).isEqualTo("BORROWER-532");
            assertThat(data.path("dueDate").asText()).isEqualTo(today.plusDays(10).toString());
            assertThat(data.path("cancelledAt").isTextual()).isTrue();
        });
        assertThat(repaymentService.scheduledDue(applicationId).pending()).isNull();

        when(repaymentCoreGateway.earlySettlementQuote(fineractLoanId, today)).thenReturn(new EarlySettlementCoreQuote(
                today, new BigDecimal("10049000.00"), new BigDecimal("10000000.00"), new BigDecimal("49000.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), 6, today.minusDays(15),
                today.plusMonths(6)));
        var quote = earlySettlementQuoteService.create(applicationId);
        assertThat(quote.coreAmount()).isEqualByComparingTo("10049000.00");
    }

    @Test
    void legacyCoreLoanBlocksSelfServicePrepaymentButStillPaysInstallmentsImmediately() {
        long applicationId = 533L;
        long fineractLoanId = 733L;
        activateLoan(applicationId, 633L, fineractLoanId, "FINORA-FINERACT-V3", "AGRIBANK_REFERENCE_FINORA_2026_V2");
        actAsBorrower("BORROWER-533");

        // D17: core V3 báo giá cả lãi các tháng tương lai (B28/B29) nên không cho tự trả trước hoặc tất toán.
        assertThatThrownBy(() -> partialPrepaymentQuoteService.create(applicationId, new BigDecimal("3000000.00")))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("PREPAYMENT_NOT_SUPPORTED_FOR_LEGACY_LOAN");
                });
        assertThatThrownBy(() -> earlySettlementQuoteService.create(applicationId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("PREPAYMENT_NOT_SUPPORTED_FOR_LEGACY_LOAN"));

        fundBorrower("1725000", "topup-legacy-533");
        LocalDate today = LocalDate.now(clock);
        when(repaymentCoreGateway.scheduledRepaymentQuote(fineractLoanId)).thenReturn(new ScheduledRepaymentCoreQuote(
                new BigDecimal("1725000.00"), BigDecimal.ZERO.setScale(2), today.plusDays(16)));
        // Core V3 tính trước lãi cả kỳ: trả sớm ghi ngay vẫn đúng lịch, không cần giữ tới ngày đến hạn.
        assertThat(repaymentService.scheduledDue(applicationId).postOn()).isNull();
        var collected = repaymentService.create(applicationId, new BigDecimal("1725000.00"), today, "legacy-533");
        assertThat(collected.postOn()).isNull();
        assertThat(repaymentService.collectedIds())
                .contains(repaymentRepository.findByRepaymentId(collected.repaymentId()).orElseThrow().getId());
        // Khoản ghi Fineract ngay không bị giữ: không có RepaymentCollected, RepaymentDistributed sẽ theo sau (D77).
        assertThat(outboxEvents("RepaymentCollected")).isEmpty();
    }

    @Test
    void overdueSeveralInstallmentsQuotesTheFullCureAndOnlyAcceptsTheOldestInstallmentAsPartial() {
        long applicationId = 535L;
        long fineractLoanId = 735L;
        activateLoan(applicationId, 635L, fineractLoanId, "FINORA-FINERACT-V4", "AGRIBANK_REFERENCE_FINORA_2026_V2");
        actAsBorrower("BORROWER-535");
        fundBorrower("6000000", "topup-cure-535");
        LocalDate today = LocalDate.now(clock);
        // B31: quá hạn ba kỳ. Template Fineract chỉ báo kỳ cũ nhất; totalOverdue là cả ba kỳ.
        when(repaymentCoreGateway.scheduledRepaymentQuote(fineractLoanId)).thenReturn(new ScheduledRepaymentCoreQuote(
                new BigDecimal("1726000.00"), new BigDecimal("5178000.00"), today.minusDays(91)));

        var due = repaymentService.scheduledDue(applicationId);
        assertThat(due.amount()).isEqualByComparingTo("5178000.00");
        assertThat(due.overdueAmount()).isEqualByComparingTo("5178000.00");
        assertThat(due.partialOverdueAmount()).isEqualByComparingTo("1726000.00");
        assertThat(due.repaymentType()).isEqualTo(com.finora.payment.domain.repayment.PaymentRepaymentType.OVERDUE_CURE);
        assertThat(due.postOn()).isNull();

        assertThatThrownBy(() -> repaymentService.create(applicationId, new BigDecimal("3452000.00"), today, "cure-535-odd"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("REPAYMENT_AMOUNT_NOT_SCHEDULED_DUE"));
        assertThat(borrowerAvailable("BORROWER-535")).isEqualByComparingTo("6000000.00");

        // Trả bớt đúng kỳ cũ nhất: vẫn là khắc phục quá hạn, ghi Fineract ngay (không giữ tới ngày đến hạn).
        var partial = repaymentService.create(applicationId, new BigDecimal("1726000.00"), today, "cure-535-partial");
        PaymentRepayment collected = repaymentRepository.findByRepaymentId(partial.repaymentId()).orElseThrow();
        assertThat(collected.getRepaymentType())
                .isEqualTo(com.finora.payment.domain.repayment.PaymentRepaymentType.OVERDUE_CURE);
        assertThat(partial.postOn()).isNull();
        assertThat(borrowerAvailable("BORROWER-535")).isEqualByComparingTo("4274000.00");
    }

    @Test
    void overdueLoanCannotQuotePartialPrepaymentSoNoFeeIsChargedOnMoneyThatOnlyCuresArrears() {
        long applicationId = 536L;
        long fineractLoanId = 736L;
        activateLoan(applicationId, 636L, fineractLoanId, "FINORA-FINERACT-V4", "AGRIBANK_REFERENCE_FINORA_2026_V2");
        actAsBorrower("BORROWER-536");
        LocalDate today = LocalDate.now(clock);
        // Kỳ chưa trả cũ nhất đến hạn 5 ngày trước: khoản vay quá hạn dù job tính quá hạn của Fineract chưa chạy.
        when(repaymentCoreGateway.partialPrepaymentSnapshot(fineractLoanId, today))
                .thenReturn(new PartialPrepaymentCoreSnapshot(today, new BigDecimal("1726000.00"),
                        new BigDecimal("10102000.00"), new BigDecimal("10000000.00"),
                        today.minusDays(5), new BigDecimal("1726000.00"), 6, today.minusMonths(1), today.plusMonths(5)));

        assertThatThrownBy(() -> partialPrepaymentQuoteService.create(applicationId, new BigDecimal("3000000.00")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PARTIAL_PREPAYMENT_LOAN_OVERDUE"));
    }

    @Test
    void repaymentTheCoreNeverReceivedIsRepostedOnceWithTheSameReferenceAfterTheSafeWindow() {
        PaymentRepayment stuck = stuckOverdueRepayment(537L, 737L, "537");
        String reference = "FINORA-REPAY-" + stuck.getRepaymentId();

        // Lần ghi core vừa kết thúc không rõ kết quả: request cũ có thể còn chạy ở Fineract nên chưa được ghi lại.
        assertThatThrownBy(() -> repaymentService.repostCorePosting(stuck.getId(), "admin-1", Duration.ofMinutes(15)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("REPAYMENT_REPOST_TOO_EARLY"));

        testClock.advance(Duration.ofMinutes(16));
        when(repaymentCoreGateway.findPostedRepayment(737L, reference)).thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.doReturn(coreBreakdown(9737L)).when(repaymentCoreGateway).postRepayment(any());
        repaymentService.repostCorePosting(stuck.getId(), "admin-1", Duration.ofMinutes(15));

        ArgumentCaptor<RepaymentCoreCommand> commands = ArgumentCaptor.forClass(RepaymentCoreCommand.class);
        verify(repaymentCoreGateway, org.mockito.Mockito.times(2)).postRepayment(commands.capture());
        assertThat(commands.getAllValues()).allSatisfy(command -> {
            assertThat(command.repaymentReference()).isEqualTo(reference);
            assertThat(command.transactionDate()).isEqualTo(stuck.corePostingDate());
            assertThat(command.amount()).isEqualByComparingTo("1726000.00");
        });
        PaymentRepayment reposted = repaymentRepository.findById(stuck.getId()).orElseThrow();
        assertThat(reposted.getStatus()).isEqualTo(PaymentRepaymentStatus.CORE_POSTED);
        assertThat(reposted.getFineractTransactionId()).isEqualTo(9737L);
        assertThat(reposted.getCoreRepostCount()).isEqualTo(1);
        assertThat(reposted.getLastCoreRepostBy()).isEqualTo("admin-1");
        assertThat(borrowerAvailable("BORROWER-537")).isEqualByComparingTo("274000.00");
    }

    @Test
    void repostRejectedAsDuplicateOfAPostingThatLandedLateIsNeverRefunded() {
        PaymentRepayment stuck = stuckOverdueRepayment(538L, 738L, "538");
        String reference = "FINORA-REPAY-" + stuck.getRepaymentId();
        testClock.advance(Duration.ofMinutes(16));
        // Tra cứu trước khi ghi lại chưa thấy; Fineract từ chối vì lần ghi cũ vừa vào; tra cứu sau đó thấy giao dịch.
        when(repaymentCoreGateway.findPostedRepayment(738L, reference))
                .thenReturn(java.util.Optional.empty(), java.util.Optional.of(coreBreakdown(9738L)));
        org.mockito.Mockito.doThrow(new com.finora.payment.integration.fineract.FineractRepaymentException(
                "FINERACT_REPAYMENT_REJECTED", "Fineract từ chối repayment với HTTP 403", false, null))
                .when(repaymentCoreGateway).postRepayment(any());

        repaymentService.repostCorePosting(stuck.getId(), "admin-1", Duration.ofMinutes(15));

        PaymentRepayment value = repaymentRepository.findById(stuck.getId()).orElseThrow();
        assertThat(value.getStatus()).isEqualTo(PaymentRepaymentStatus.CORE_POSTED);
        assertThat(value.getFineractTransactionId()).isEqualTo(9738L);
        assertThat(borrowerAvailable("BORROWER-538")).isEqualByComparingTo("274000.00");
    }

    @Test
    void repostTheCoreRejectsAndStillDoesNotHaveIsRefundedToTheBorrower() {
        PaymentRepayment stuck = stuckOverdueRepayment(539L, 739L, "539");
        String reference = "FINORA-REPAY-" + stuck.getRepaymentId();
        testClock.advance(Duration.ofMinutes(16));
        when(repaymentCoreGateway.findPostedRepayment(739L, reference)).thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.doThrow(new com.finora.payment.integration.fineract.FineractRepaymentException(
                "FINERACT_REPAYMENT_REJECTED", "Fineract từ chối repayment với HTTP 400", false, null))
                .when(repaymentCoreGateway).postRepayment(any());

        repaymentService.repostCorePosting(stuck.getId(), "admin-1", Duration.ofMinutes(15));

        PaymentRepayment value = repaymentRepository.findById(stuck.getId()).orElseThrow();
        assertThat(value.getStatus()).isEqualTo(PaymentRepaymentStatus.FAILED);
        assertThat(value.getErrorCode()).isEqualTo("FINERACT_REPAYMENT_REJECTED");
        assertThat(borrowerAvailable("BORROWER-539")).isEqualByComparingTo("2000000.00");
    }

    /** Khoản khắc phục quá hạn đã thu tiền, lần ghi Fineract không rõ kết quả → RECONCILIATION_REQUIRED. */
    private PaymentRepayment stuckOverdueRepayment(long applicationId, long fineractLoanId, String suffix) {
        activateLoan(applicationId, 600L + applicationId - 500L, fineractLoanId, "FINORA-FINERACT-V4",
                "AGRIBANK_REFERENCE_FINORA_2026_V2");
        actAsBorrower("BORROWER-" + suffix);
        fundBorrower("2000000", "topup-repost-" + suffix);
        LocalDate today = LocalDate.now(clock);
        when(repaymentCoreGateway.scheduledRepaymentQuote(fineractLoanId)).thenReturn(new ScheduledRepaymentCoreQuote(
                new BigDecimal("1726000.00"), new BigDecimal("1726000.00"), today.minusDays(3)));
        var collected = repaymentService.create(applicationId, new BigDecimal("1726000.00"), today, "repost-" + suffix);
        PaymentRepayment value = repaymentRepository.findByRepaymentId(collected.repaymentId()).orElseThrow();
        org.mockito.Mockito.doThrow(new com.finora.payment.integration.fineract.FineractRepaymentException(
                "FINERACT_REPAYMENT_OUTCOME_UNKNOWN", "Mất kết nối khi ghi repayment", true, null))
                .when(repaymentCoreGateway).postRepayment(any());
        repaymentService.postToCore(value.getId());
        PaymentRepayment stuck = repaymentRepository.findById(value.getId()).orElseThrow();
        assertThat(stuck.getStatus()).isEqualTo(PaymentRepaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(borrowerAvailable("BORROWER-" + suffix)).isEqualByComparingTo("274000.00");
        return stuck;
    }

    private static PaymentRepayment.CoreBreakdown coreBreakdown(long transactionId) {
        return new PaymentRepayment.CoreBreakdown(transactionId, new BigDecimal("1624000.00"),
                new BigDecimal("102000.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("8376000.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), new BigDecimal("8376000.00"), BigDecimal.ZERO.setScale(2),
                LocalDate.now().plusMonths(1), new BigDecimal("1726000.00"));
    }

    @Test
    void prepaymentQuoteCannotBeExecutedAfterAnotherRepaymentChangedTheLoan() {
        long applicationId = 534L;
        long fineractLoanId = 734L;
        activateLoan(applicationId, 634L, fineractLoanId, "FINORA-FINERACT-V4", "AGRIBANK_REFERENCE_FINORA_2026_V2");
        actAsBorrower("BORROWER-534");
        fundBorrower("8000000", "topup-stale-534");
        LocalDate today = LocalDate.now(clock);
        when(repaymentCoreGateway.partialPrepaymentSnapshot(fineractLoanId, today))
                .thenReturn(new PartialPrepaymentCoreSnapshot(today, new BigDecimal("1725000.00"),
                        new BigDecimal("10049000.00"), new BigDecimal("10000000.00"),
                        today, new BigDecimal("1725000.00"), 6, today.minusMonths(1), today.plusMonths(5)));
        var quote = partialPrepaymentQuoteService.create(applicationId, new BigDecimal("3000000.00"));

        // Kỳ đến hạn hôm nay được trả riêng sau khi lập báo giá: báo giá cũ sẽ thu kỳ này lần hai.
        when(repaymentCoreGateway.scheduledRepaymentQuote(fineractLoanId)).thenReturn(new ScheduledRepaymentCoreQuote(
                new BigDecimal("1725000.00"), BigDecimal.ZERO.setScale(2), today));
        var installment = repaymentService.create(applicationId, new BigDecimal("1725000.00"), today, "stale-534");
        assertThat(installment.postOn()).isNull();
        assertThatThrownBy(() -> repaymentService.createPartialPrepayment(quote.quoteId(), "stale-534-prepay"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("PARTIAL_PREPAYMENT_QUOTE_STALE"));
        assertThat(borrowerAvailable("BORROWER-534")).isEqualByComparingTo("6275000.00");
    }

    private List<com.finora.payment.domain.outbox.PaymentOutboxEvent> outboxEvents(String eventType) {
        return outboxRepository.findAll().stream().filter(event -> eventType.equals(event.getEventType())).toList();
    }

    private JsonNode json(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void issueNote(long applicationId, long listingId, long noteId, String investorId) {
        servicingProjectionService.updateOwnership(UUID.randomUUID(), new NoteOwnershipChangedEventData(
                applicationId, listingId, "VND", "ISSUED", "NOTE-ISSUE-" + applicationId,
                Instant.parse("2026-10-01T00:00:00Z"),
                List.of(new NoteOwnershipChangedEventData.NoteOwner(
                        noteId, "NOTE-" + applicationId, investorId, "10000000.00"))));
    }

    private void fundBorrower(String amount, String idempotencyKey) {
        TopUpResponse topUp = topUpService.create(new BigDecimal(amount), idempotencyKey);
        topUpService.completeMock(topUp.topUpId());
    }

    private BigDecimal borrowerAvailable(String borrowerId) {
        return walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(WalletOwnerType.BORROWER, borrowerId, "VND")
                .orElseThrow().getAvailableBalance();
    }

    private long activateLoanForQuote(long applicationId, long listingId, long fineractLoanId, String policyVersion) {
        return activateLoan(applicationId, listingId, fineractLoanId, "FINORA-FINERACT-V4", policyVersion);
    }

    private long activateLoan(long applicationId, long listingId, long fineractLoanId, String coreVersion,
            String policyVersion) {
        UUID sagaId = UUID.randomUUID();
        Instant disbursedAt = Instant.parse("2026-10-01T00:00:00Z");
        String borrower = "BORROWER-" + applicationId;
        PaymentDisbursement disbursement = PaymentDisbursement.requested(
                sagaId, applicationId, "LC-" + applicationId, listingId, borrower,
                new BigDecimal("10000000.00"), "VND", "MOCK", "[]", disbursedAt);
        disbursement.start(disbursedAt, disbursedAt.plusSeconds(120));
        disbursement.complete("MOCK-" + applicationId, UUID.randomUUID(), disbursedAt);
        disbursementRepository.saveAndFlush(disbursement);
        servicingProjectionService.activateLoan(UUID.randomUUID(), new LoanDisbursedEventData(
                sagaId, applicationId, "LA-" + applicationId, "LC-" + applicationId, listingId, "10000000.00", "VND",
                "MOCK-" + applicationId, fineractLoanId, coreVersion, disbursedAt, policyVersion));
        return fineractLoanId;
    }

    private boolean postAfterBarrier(
            LedgerPostingCommand command,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            postingService.post(command);
            return true;
        } catch (BusinessException rejected) {
            assertThat(rejected.getCode()).isEqualTo("PAYMENT_INSUFFICIENT_BALANCE");
            return false;
        }
    }

    private static LedgerPostingCommand deposit(
            String key, String referenceId, UUID walletId, String amount
    ) {
        return new LedgerPostingCommand(
                key, LedgerTransactionType.DEPOSIT, "TEST_DEPOSIT", referenceId, "VND",
                List.of(clearing(LedgerDirection.DEBIT, amount),
                        wallet(walletId, LedgerDirection.CREDIT, amount)));
    }

    private static LedgerPostingCommand withdrawal(
            String key, String referenceId, UUID walletId, String amount
    ) {
        return new LedgerPostingCommand(
                key, LedgerTransactionType.WITHDRAWAL, "TEST_WITHDRAWAL", referenceId, "VND",
                List.of(wallet(walletId, LedgerDirection.DEBIT, amount),
                        clearing(LedgerDirection.CREDIT, amount)));
    }

    private static LedgerPostingEntryCommand clearing(LedgerDirection direction, String amount) {
        return new LedgerPostingEntryCommand(
                null, "SYS_TEST_CLEARING", LedgerBalanceBucket.CLEARING,
                direction, new BigDecimal(amount));
    }

    private static LedgerPostingEntryCommand wallet(UUID walletId, LedgerDirection direction, String amount) {
        return new LedgerPostingEntryCommand(
                walletId, null, LedgerBalanceBucket.AVAILABLE, direction, new BigDecimal(amount));
    }

    /** Service account của finora-investment: không có user_id, mang client role on_behalf. */
    private static void actAsInvestmentService() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("service-account-finora-investment-client")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority(HoldTransferService.ON_BEHALF_AUTHORITY)),
                "service-account-finora-investment-client"));
    }

    private static void actAs(String userId) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(userId)
                .claim("user_id", userId)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_INVESTOR")), userId));
    }

    private static void actAsBorrower(String userId) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(userId)
                .claim("user_id", userId)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_BORROWER")), userId));
    }
}
