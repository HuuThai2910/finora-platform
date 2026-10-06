package com.finora.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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
import com.finora.payment.integration.fineract.PartialPrepaymentCoreSnapshot;
import com.finora.payment.integration.fineract.ScheduledRepaymentCoreQuote;
import com.finora.payment.service.disbursement.PaymentDisbursementService;
import com.finora.payment.service.hold.HoldTransferService;
import com.finora.payment.service.topup.TopUpService;
import com.finora.payment.service.repayment.PaymentRepaymentService;
import com.finora.payment.service.repayment.PartialPrepaymentQuoteService;
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
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.boot.test.mock.mockito.MockBean;
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
    private Clock clock;

    @MockBean
    private RepaymentCoreGateway repaymentCoreGateway;

    @BeforeEach
    void cleanLedger() {
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

    @Test
    void completedLegacyDisbursementCreditsBorrowerExactlyOnce() {
        UUID sagaId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-28T12:00:00Z");
        PaymentDisbursement legacy = PaymentDisbursement.requested(
                sagaId, 102L, "LC-LEGACY", 202L, "BORROWER-LEGACY",
                new BigDecimal("7000000.00"), "VND", "MOCK", "[]", now);
        legacy.start(now);
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
    void scheduledRepaymentMovesMoneyThroughCoreAndDistributesToCurrentNoteOwner() {
        long applicationId = 501L;
        long listingId = 601L;
        long fineractLoanId = 701L;
        UUID sagaId = UUID.randomUUID();
        Instant disbursedAt = Instant.parse("2026-10-01T00:00:00Z");
        PaymentDisbursement disbursement = PaymentDisbursement.requested(
                sagaId, applicationId, "LC-501", listingId, "BORROWER-501",
                new BigDecimal("10000000.00"), "VND", "MOCK", "[]", disbursedAt);
        disbursement.start(disbursedAt);
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
    void partialPrepaymentQuotesCollectsPostsAndDistributesWithV2CoreAccount() {
        long applicationId = 502L;
        long listingId = 602L;
        long fineractLoanId = 702L;
        UUID sagaId = UUID.randomUUID();
        Instant disbursedAt = Instant.parse("2026-10-01T00:00:00Z");
        PaymentDisbursement disbursement = PaymentDisbursement.requested(
                sagaId, applicationId, "LC-502", listingId, "BORROWER-502",
                new BigDecimal("10000000.00"), "VND", "MOCK", "[]", disbursedAt);
        disbursement.start(disbursedAt);
        disbursement.complete("MOCK-502", UUID.randomUUID(), disbursedAt);
        disbursementRepository.saveAndFlush(disbursement);
        servicingProjectionService.activateLoan(UUID.randomUUID(), new LoanDisbursedEventData(
                sagaId, applicationId, "LA-502", "LC-502", listingId, "10000000.00", "VND",
                "MOCK-502", fineractLoanId, "FINORA-FINERACT-V2", disbursedAt));
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
        var collected = repaymentService.createPartialPrepayment(quote.quoteId(), "partial-it-502");
        PaymentRepayment repayment = repaymentRepository.findByRepaymentId(collected.repaymentId()).orElseThrow();
        repaymentService.postToCore(repayment.getId());
        repaymentService.distribute(repayment.getId());

        PaymentRepayment completed = repaymentRepository.findByRepaymentId(collected.repaymentId()).orElseThrow();
        PaymentWallet borrower = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.BORROWER, "BORROWER-502", "VND").orElseThrow();
        PaymentWallet investor = walletRepository.findByOwnerTypeAndOwnerIdAndCurrency(
                WalletOwnerType.INVESTOR, "INVESTOR-502", "VND").orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PaymentRepaymentStatus.COMPLETED);
        assertThat(completed.getCoreAmount()).isEqualByComparingTo("5000000.00");
        assertThat(completed.getPlatformFee()).isEqualByComparingTo("100000.00");
        assertThat(borrower.getAvailableBalance()).isEqualByComparingTo("0.00");
        assertThat(investor.getAvailableBalance()).isEqualByComparingTo("5000000.00");
        assertThat(noteOwnershipRepository.findById(802L).orElseThrow().getOutstandingPrincipal())
                .isEqualByComparingTo("5000000.00");
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
