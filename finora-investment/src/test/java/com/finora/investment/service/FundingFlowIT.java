package com.finora.investment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.common.enums.investment.NoteStatus;
import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.investment.dto.request.CreateListingRequest;
import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.ListingInvestorResponse;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.dto.response.OrderResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.messaging.event.InvestorSignatureRequestedEventData;
import com.finora.investment.messaging.event.LoanContractActivatedEventData;
import com.finora.investment.messaging.event.LoanDisbursedEventData;
import com.finora.investment.messaging.event.LoanFundingCancelledEventData;
import com.finora.investment.messaging.event.LoanFundingRequestedEventData;
import com.finora.investment.service.messaging.LoanFundingCancelledHandler;
import com.finora.common.enums.investment.OrderStatus;
import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.impl.StubPaymentClient;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.InvestmentOrderRepository;
import com.finora.investment.service.impl.FundingMaintenanceService;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.InvestmentOutboxEventRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.service.messaging.ContractLifecycleEventHandler;
import com.finora.investment.service.messaging.LoanDisbursedEventHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Kiểm chứng luồng gọi vốn F04 trên PostgreSQL thật.
 *
 * <p>Trọng tâm là những thứ chỉ sai khi chạy thật: khóa bi quan chống overfund giữa nhiều
 * luồng, idempotency của lệnh đặt vốn, và việc xé nhỏ vốn thành Note sau giải ngân.</p>
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "finora.investment.payment.mode=stub",
        "finora.investment.auto-invest.enabled=false",
        "finora.investment.order-book.settlement.enabled=false",
        "finora.investment.maintenance.enabled=false",
        "finora.investment.outbox.publisher-delay=3600000"
})
@Testcontainers
class FundingFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_investment_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired
    private MarketListingService listingService;

    @Autowired
    private FundingService fundingService;

    @Autowired
    private NoteIssuanceService noteIssuanceService;

    @Autowired
    private MarketListingRepository listingRepository;

    @Autowired
    private InvestmentCommitmentRepository commitmentRepository;

    @Autowired
    private InvestmentNoteRepository noteRepository;

    @Autowired
    private InvestmentOutboxEventRepository outboxRepository;

    @Autowired
    private ContractLifecycleEventHandler contractLifecycleEventHandler;

    @Autowired
    private LoanDisbursedEventHandler loanDisbursedEventHandler;

    @Autowired
    private LoanFundingCancelledHandler loanFundingCancelledHandler;

    @Autowired
    private com.finora.investment.service.messaging.LoanFundingRequestedHandler loanFundingRequestedHandler;

    @Autowired
    private InvestmentOrderRepository orderRepository;

    @Autowired
    private OrderTransactionService orderTransactions;

    @Autowired
    private FundingMaintenanceService fundingMaintenance;

    @Autowired
    private PaymentClient paymentClient;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private PortfolioService portfolioService;

    @Autowired
    private com.finora.investment.service.impl.OrderIncidentService orderIncidentService;

    private static final AtomicInteger LOAN_SEQUENCE = new AtomicInteger(1000);

    /**
     * Đặt SecurityContext giả mang claim {@code user_id} của một nhà đầu tư.
     *
     * <p>{@code CurrentUserProvider} đọc danh tính từ access token, nên test phải dựng
     * đúng ngữ cảnh đó để mô phỏng nhiều nhà đầu tư khác nhau. Dựng {@link Jwt} trực
     * tiếp thay vì gọi Keycloak thật: bài kiểm thử này xác minh nghiệp vụ gọi vốn,
     * còn việc kiểm chữ ký token là trách nhiệm của Spring Security.</p>
     */
    private void actAs(String investorId) {
        actAs(investorId, "ROLE_INVESTOR");
    }

    /** Như {@link #actAs(String)} nhưng chỉ định vai trò, để kiểm thử nhánh quản trị. */
    private void actAs(String userId, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("sub", userId)
                .claim("user_id", userId)
                .claim("ekyc_status", "VERIFIED")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)), userId));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Xem được ai đã góp vốn, kèm phần trăm và phần vốn đã hủy")
    void listsInvestorsOfListing() {
        actAs("INVESTOR-LIST-A");
        MarketListingResponse listing = newListing("10000000.00", "1000000.00");
        fundingService.placeOrder(listing.listingId(), "key-list-a",
                new PlaceOrderRequest(new BigDecimal("6000000.00"), "INVESTOR_RISK_V1"));

        actAs("INVESTOR-LIST-B");
        OrderResponse cancelled = fundingService.placeOrder(listing.listingId(), "key-list-b",
                new PlaceOrderRequest(new BigDecimal("2000000.00"), "INVESTOR_RISK_V1"));
        fundingService.cancelOrder(cancelled.orderReference());

        actAs("ADMIN-LIST", "ROLE_ADMIN");
        List<ListingInvestorResponse> investors = listingService.investorsOf(listing.listingId());

        // Cả phần vốn đã hủy vẫn hiện, để quản trị thấy lịch sử đầy đủ.
        assertThat(investors).hasSize(2);
        assertThat(investors).extracting(ListingInvestorResponse::investorId)
                .containsExactlyInAnyOrder("INVESTOR-LIST-A", "INVESTOR-LIST-B");

        ListingInvestorResponse active = investors.stream()
                .filter(item -> "INVESTOR-LIST-A".equals(item.investorId()))
                .findFirst()
                .orElseThrow();
        assertThat(active.status()).isEqualTo(CommitmentStatus.ACTIVE.name());
        assertThat(active.noteCount()).isEqualTo(6);
        assertThat(active.sharePercent()).isEqualByComparingTo("60.000000");

        assertThat(investors.stream()
                .filter(item -> "INVESTOR-LIST-B".equals(item.investorId()))
                .findFirst()
                .orElseThrow()
                .status()).isEqualTo(CommitmentStatus.CANCELLED.name());
    }

    /**
     * Dựng listing dưới vai ADMIN rồi trả lại vai đang đóng trước đó.
     *
     * <p>Tạo listing là thao tác quản trị nên {@code MarketListingService} gọi
     * {@code adminId()}, trong khi phần lớn bài kiểm thử đang đóng vai nhà đầu tư.
     * Khôi phục authentication cũ để thứ tự gọi trong mỗi bài không bị đổi nghĩa.</p>
     */
    private MarketListingResponse newListing(String target, String denomination) {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        actAs("ADMIN-TEST", "ROLE_ADMIN");
        try {
            return createListing(target, denomination);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private MarketListingResponse createListing(String target, String denomination) {
        return createListing(target, denomination, 700);
    }

    private MarketListingResponse createListing(String target, String denomination, Integer creditScore) {
        long loanId = LOAN_SEQUENCE.incrementAndGet();
        return listingService.createListingFrom(CreateListingRequest.builder()
                .loanId(loanId)
                .applicationNumber("LA-TEST-" + loanId)
                .listingVersion(1)
                .fundingRound(1)
                .termsVersion("LOAN_TERMS_V1")
                .termsHash("a".repeat(64))
                .contractNumber(null)
                .productCode("SP-01")
                .purpose("Vốn kinh doanh")
                .region("TP.HCM")
                .creditGrade("B")
                .creditScore(creditScore)
                .targetAmount(new BigDecimal(target))
                .annualInterestRate(new BigDecimal("15.0000"))
                .termMonths(12)
                .repaymentMethod("ANNUITY")
                .build());
    }

    @Test
    @DisplayName("Credit score chưa có vẫn tạo listing, không tự bịa điểm mặc định")
    void createsListingWithoutOptionalCreditScore() {
        actAs("ADMIN-OPTIONAL-SCORE", "ROLE_ADMIN");

        MarketListingResponse listing = createListing("10000000.00", "1000000.00", null);

        assertThat(listing.creditScore()).isNull();
        assertThat(listingRepository.findById(listing.listingId()).orElseThrow().getCreditScore())
                .isNull();
    }

    @Test
    @DisplayName("Gọi đủ vốn thì đóng sàn và ghi đúng một event LoanFullyFunded")
    void fullFundingClosesListingOnce() {
        actAs("INVESTOR-A");
        MarketListingResponse listing = newListing("10000000.00", "1000000.00");

        fundingService.placeOrder(listing.listingId(), "key-a-1",
                new PlaceOrderRequest(new BigDecimal("6000000.00"), "INVESTOR_RISK_V1"));

        actAs("INVESTOR-B");
        OrderResponse second = fundingService.placeOrder(listing.listingId(), "key-b-1",
                new PlaceOrderRequest(new BigDecimal("4000000.00"), "INVESTOR_RISK_V1"));

        assertThat(second.status()).isEqualTo("COMMITTED");

        var saved = listingRepository.findById(listing.listingId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ListingStatus.FULLY_FUNDED);
        assertThat(saved.getCommittedAmount()).isEqualByComparingTo("10000000.00");
        assertThat(outboxRepository.findAll().stream()
                .filter(event -> saved.getApplicationNumber().equals(event.getAggregateId()))
                .toList())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo("LoanFullyFunded");
                    assertThat(event.getEventVersion()).isEqualTo(2);
                    assertThat(event.getPayloadJson()).contains("paymentHoldReference");
                });
    }

    @Test
    @DisplayName("Nhiều nhà đầu tư đặt lệnh đồng thời không làm vượt mục tiêu gọi vốn")
    void concurrentOrdersNeverOverfund() throws Exception {
        actAs("INVESTOR-SETUP");
        // Mục tiêu 10 triệu, mỗi lệnh 1 triệu: chỉ 10 trong 20 lệnh được phép thành công.
        MarketListingResponse listing = newListing("10000000.00", "1000000.00");

        int attempts = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<String>> tasks = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            final int index = i;
            tasks.add(() -> {
                actAs("INVESTOR-" + index);
                try {
                    return fundingService.placeOrder(
                            listing.listingId(),
                            "key-concurrent-" + index,
                            new PlaceOrderRequest(new BigDecimal("1000000.00"), "INVESTOR_RISK_V1")
                    ).status();
                } catch (RuntimeException e) {
                    // Hết chỗ là kết quả hợp lệ của cuộc đua, không phải lỗi hệ thống.
                    return "REJECTED";
                } finally {
                    // SecurityContext theo từng luồng, phải dọn để luồng của pool
                    // không mang danh tính sang tác vụ kế tiếp.
                    SecurityContextHolder.clearContext();
                }
            });
        }

        List<Future<String>> results = pool.invokeAll(tasks);
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        long committed = 0;
        for (Future<String> result : results) {
            if ("COMMITTED".equals(result.get())) {
                committed++;
            }
        }

        var saved = listingRepository.findById(listing.listingId()).orElseThrow();
        // Bất biến quan trọng nhất: không bao giờ nhận quá mục tiêu, dù có bao nhiêu luồng.
        assertThat(saved.getCommittedAmount()).isEqualByComparingTo("10000000.00");
        assertThat(committed).isEqualTo(10);
        assertThat(saved.getStatus()).isEqualTo(ListingStatus.FULLY_FUNDED);
    }

    @Test
    @DisplayName("Gửi lại cùng Idempotency-Key không tạo lệnh thứ hai")
    void duplicateIdempotencyKeyReturnsSameOrder() {
        actAs("INVESTOR-IDEM");
        MarketListingResponse listing = newListing("10000000.00", "1000000.00");

        PlaceOrderRequest request = new PlaceOrderRequest(new BigDecimal("2000000.00"), "INVESTOR_RISK_V1");
        OrderResponse first = fundingService.placeOrder(listing.listingId(), "key-dup", request);
        OrderResponse second = fundingService.placeOrder(listing.listingId(), "key-dup", request);

        assertThat(second.orderReference()).isEqualTo(first.orderReference());

        var saved = listingRepository.findById(listing.listingId()).orElseThrow();
        // Chỉ cộng vào tiến độ một lần, dù đã gọi API hai lần.
        assertThat(saved.getCommittedAmount()).isEqualByComparingTo("2000000.00");
    }

    @Test
    @DisplayName("Dùng lại khóa idempotency cho số tiền khác bị từ chối")
    void reusedIdempotencyKeyWithDifferentAmountIsRejected() {
        actAs("INVESTOR-REUSE");
        MarketListingResponse listing = newListing("10000000.00", "1000000.00");

        fundingService.placeOrder(listing.listingId(), "key-reuse",
                new PlaceOrderRequest(new BigDecimal("1000000.00"), "INVESTOR_RISK_V1"));

        assertThatThrownBy(() -> fundingService.placeOrder(listing.listingId(), "key-reuse",
                new PlaceOrderRequest(new BigDecimal("3000000.00"), "INVESTOR_RISK_V1")))
                .isInstanceOf(InvestmentDomainException.class)
                .hasMessageContaining("idempotency");
    }

    @Test
    @DisplayName("Xé nhỏ vốn thành Notes đúng số lượng và chạy lại không nhân đôi")
    void activateNotesSplitsCapitalAndIsIdempotent() {
        actAs("INVESTOR-NOTE");
        MarketListingResponse listing = newListing("10000000.00", "1000000.00");

        fundingService.placeOrder(listing.listingId(), "key-note-1",
                new PlaceOrderRequest(new BigDecimal("6000000.00"), "INVESTOR_RISK_V1"));
        actAs("INVESTOR-NOTE-2");
        fundingService.placeOrder(listing.listingId(), "key-note-2",
                new PlaceOrderRequest(new BigDecimal("4000000.00"), "INVESTOR_RISK_V1"));

        assertThat(noteIssuanceService.finalizeCommitments(listing.listingId())).isEqualTo(2);

        int issued = noteIssuanceService.activateNotes(listing.listingId());
        // 6 triệu + 4 triệu, mệnh giá 1 triệu → đúng 10 Note.
        assertThat(issued).isEqualTo(10);

        // Saga chạy lại bước phát hành sau lỗi giữa chừng không được nhân đôi quyền sở hữu.
        assertThat(noteIssuanceService.activateNotes(listing.listingId())).isZero();

        List<com.finora.investment.domain.note.InvestmentNote> notes =
                noteRepository.findByLoanIdAndStatus(
                        listingRepository.findById(listing.listingId()).orElseThrow().getLoanId(),
                        NoteStatus.ACTIVE);
        assertThat(notes).hasSize(10);
        assertThat(notes).allSatisfy(note ->
                assertThat(note.getPrincipalAmount()).isEqualByComparingTo("1000000.00"));
    }

    @Test
    @DisplayName("LoanDisbursed hợp lệ mới finalise vốn, phát hành Note và lưu bằng chứng")
    void loanDisbursedFinalizesInvestmentExactlyOnce() {
        actAs("INVESTOR-DISBURSE");
        MarketListingResponse listingResponse = newListing("3000000.00", "1000000.00");
        fundingService.placeOrder(listingResponse.listingId(), "key-disburse",
                new PlaceOrderRequest(new BigDecimal("3000000.00"), "INVESTOR_RISK_V1"));

        var listing = listingRepository.findById(listingResponse.listingId()).orElseThrow();
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        String contractNumber = "LC-TEST-" + listing.getLoanId();
        String documentHash = "b".repeat(64);
        String receiptHash = "c".repeat(64);

        contractLifecycleEventHandler.handleSignatureRequested(UUID.randomUUID(), now,
                new InvestorSignatureRequestedEventData(
                        listing.getLoanId(), listing.getApplicationNumber(), listing.getId(), contractNumber,
                        documentHash, documentHash, listing.getAllocationVersion(), listing.getAllocationHash(),
                        1, now.plusSeconds(3600)));
        contractLifecycleEventHandler.handleActivated(UUID.randomUUID(), now.plusSeconds(1),
                new LoanContractActivatedEventData(
                        listing.getLoanId(), listing.getApplicationNumber(), contractNumber, listing.getId(),
                        documentHash, receiptHash, listing.getAllocationVersion(), listing.getAllocationHash(),
                        now.plusSeconds(1)));

        UUID sagaId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        LoanDisbursedEventData event = new LoanDisbursedEventData(
                sagaId, listing.getLoanId(), listing.getApplicationNumber(), contractNumber, listing.getId(),
                "3000000.00", "VND", "MOCK-PAYMENT-1", 9001L,
                "FINORA-FINERACT-V2", now.plusSeconds(2), "LN-DISBURSE-" + listing.getLoanId(),
                java.time.LocalDate.of(2027, 3, 27));
        loanDisbursedEventHandler.handle(eventId, now.plusSeconds(2), event);
        loanDisbursedEventHandler.handle(eventId, now.plusSeconds(2), event);

        var completed = listingRepository.findById(listing.getId()).orElseThrow();
        assertThat(completed.getContractStatus()).isEqualTo("EFFECTIVE");
        assertThat(completed.getDisbursementSagaId()).isEqualTo(sagaId);
        assertThat(completed.getPaymentReference()).isEqualTo("MOCK-PAYMENT-1");
        assertThat(completed.getFineractLoanId()).isEqualTo(9001L);
        assertThat(commitmentRepository.findByListingIdAndStatus(
                listing.getId(), CommitmentStatus.FINALIZED)).hasSize(1);
        assertThat(noteRepository.findByLoanIdAndStatus(listing.getLoanId(), NoteStatus.ACTIVE)).hasSize(3);
        // B34: vị thế có ngày đáo hạn theo core ngay sau giải ngân, không phải chờ lần cơ cấu đầu tiên.
        actAs("INVESTOR-DISBURSE");
        assertThat(portfolioService.portfolio().positions()).singleElement()
                .satisfies(position -> assertThat(position.maturityDate()).isEqualTo(java.time.LocalDate.of(2027, 3, 27)));
    }

    @Test
    @DisplayName("LoanDisbursed đến trước event Contract vẫn phát hành Note; event Contract đến muộn không làm trạng thái lùi")
    void loanDisbursedBeforeContractEventsStillIssuesNotesAndLateEventsDoNotRegress() {
        actAs("INVESTOR-ORDER-INDEPENDENT");
        MarketListingResponse listingResponse = newListing("3000000.00", "1000000.00");
        fundingService.placeOrder(listingResponse.listingId(), "key-order-independent",
                new PlaceOrderRequest(new BigDecimal("3000000.00"), "INVESTOR_RISK_V1"));
        var listing = listingRepository.findById(listingResponse.listingId()).orElseThrow();
        Instant now = Instant.parse("2026-10-17T03:00:00Z");
        String contractNumber = "LC-ORDER-" + listing.getLoanId();
        String documentHash = "d".repeat(64);

        // Topic disbursed được xử lý trước hai topic Contract (khác topic nên Kafka không bảo đảm thứ tự).
        loanDisbursedEventHandler.handle(UUID.randomUUID(), now, new LoanDisbursedEventData(
                UUID.randomUUID(), listing.getLoanId(), listing.getApplicationNumber(), contractNumber, listing.getId(),
                "3000000.00", "VND", "MOCK-PAYMENT-ORDER", 9101L, "FINORA-FINERACT-V3", now));

        var disbursed = listingRepository.findById(listing.getId()).orElseThrow();
        assertThat(disbursed.getContractNumber()).isEqualTo(contractNumber);
        assertThat(disbursed.getContractStatus()).isEqualTo("EFFECTIVE");
        assertThat(noteRepository.findByLoanIdAndStatus(listing.getLoanId(), NoteStatus.ACTIVE)).hasSize(3);

        contractLifecycleEventHandler.handleSignatureRequested(UUID.randomUUID(), now.plusSeconds(1),
                new InvestorSignatureRequestedEventData(
                        listing.getLoanId(), listing.getApplicationNumber(), listing.getId(), contractNumber,
                        documentHash, documentHash, listing.getAllocationVersion(), listing.getAllocationHash(),
                        1, now.plusSeconds(3600)));
        assertThat(listingRepository.findById(listing.getId()).orElseThrow().getContractStatus()).isEqualTo("EFFECTIVE");

        contractLifecycleEventHandler.handleActivated(UUID.randomUUID(), now.plusSeconds(2),
                new LoanContractActivatedEventData(
                        listing.getLoanId(), listing.getApplicationNumber(), contractNumber, listing.getId(),
                        documentHash, "e".repeat(64), listing.getAllocationVersion(), listing.getAllocationHash(),
                        now.plusSeconds(2)));
        var activated = listingRepository.findById(listing.getId()).orElseThrow();
        assertThat(activated.getContractStatus()).isEqualTo("EFFECTIVE");
        assertThat(activated.getContractReceiptHash()).isEqualTo("e".repeat(64));

        // Tham chiếu Contract mâu thuẫn vẫn bị từ chối (fail closed).
        assertThatThrownBy(() -> loanDisbursedEventHandler.handle(UUID.randomUUID(), now.plusSeconds(3),
                new LoanDisbursedEventData(UUID.randomUUID(), listing.getLoanId(), listing.getApplicationNumber(),
                        "LC-OTHER", listing.getId(), "3000000.00", "VND", "MOCK-PAYMENT-OTHER", 9102L,
                        "FINORA-FINERACT-V3", now.plusSeconds(3))))
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("LOAN_DISBURSED_MISMATCH");
    }

    @Test
    @DisplayName("Lệnh sơ cấp phải xác nhận đúng bản công bố rủi ro hiện hành và lưu lại version/thời điểm")
    void primaryOrderRequiresCurrentRiskAcknowledgement() {
        actAs("INVESTOR-RISK-ACK");
        MarketListingResponse listing = newListing("3000000.00", "1000000.00");

        assertThatThrownBy(() -> fundingService.placeOrder(listing.listingId(), "key-risk-missing",
                new PlaceOrderRequest(new BigDecimal("1000000.00"))))
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("RISK_ACKNOWLEDGEMENT_REQUIRED");
        assertThatThrownBy(() -> fundingService.placeOrder(listing.listingId(), "key-risk-old",
                new PlaceOrderRequest(new BigDecimal("1000000.00"), "INVESTOR_RISK_V0")))
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("RISK_DISCLOSURE_OUTDATED");
        // Hai lệnh bị từ chối không tạo bản ghi lệnh hay giữ tiền.
        assertThat(orderRepository.findAll()).noneMatch(order -> "INVESTOR-RISK-ACK".equals(order.getInvestorId()));

        OrderResponse accepted = fundingService.placeOrder(listing.listingId(), "key-risk-ok",
                new PlaceOrderRequest(new BigDecimal("1000000.00"), "INVESTOR_RISK_V1"));
        var stored = orderRepository.findByOrderReference(accepted.orderReference()).orElseThrow();
        assertThat(stored.getRiskAcknowledgedVersion()).isEqualTo("INVESTOR_RISK_V1");
        assertThat(stored.getRiskAcknowledgedAt()).isNotNull();

        // Gửi lại cùng khóa của lệnh đã tạo là hỏi kết quả lệnh cũ: không đòi xác nhận lần nữa, không tạo lệnh mới.
        OrderResponse replay = fundingService.placeOrder(listing.listingId(), "key-risk-ok",
                new PlaceOrderRequest(new BigDecimal("1000000.00")));
        assertThat(replay.orderReference()).isEqualTo(accepted.orderReference());
    }

    @Test
    @DisplayName("Thao tác khóa vốn/phát hành Note của quản trị bị chặn khi khoản vay chưa giải ngân")
    void adminRepairActionsRequireDisbursementEvidence() {
        actAs("INVESTOR-ADMIN-GUARD");
        MarketListingResponse listing = newListing("2000000.00", "1000000.00");
        fundingService.placeOrder(listing.listingId(), "key-admin-guard",
                new PlaceOrderRequest(new BigDecimal("2000000.00"), "INVESTOR_RISK_V1"));

        assertThatThrownBy(() -> noteIssuanceService.finalizeCommitmentsAfterDisbursement(listing.listingId()))
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("LISTING_NOT_DISBURSED");
        assertThatThrownBy(() -> noteIssuanceService.activateNotesAfterDisbursement(listing.listingId()))
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("LISTING_NOT_DISBURSED");
        // Tiền nhà đầu tư vẫn chỉ là khoản giữ: phần vốn còn ACTIVE nên bù trừ hủy gọi vốn vẫn hoạt động.
        assertThat(commitmentRepository.findByListingIdAndStatus(listing.listingId(), CommitmentStatus.ACTIVE))
                .hasSize(1);

        // Sau khi Loan xác nhận giải ngân, thao tác sửa lỗi chạy được và gọi lại không phát hành Note thứ hai.
        var stored = listingRepository.findById(listing.listingId()).orElseThrow();
        Instant now = Instant.parse("2026-10-17T03:00:00Z");
        loanDisbursedEventHandler.handle(UUID.randomUUID(), now, new LoanDisbursedEventData(
                UUID.randomUUID(), stored.getLoanId(), stored.getApplicationNumber(), "LC-GUARD-" + stored.getLoanId(),
                stored.getId(), "2000000.00", "VND", "MOCK-PAYMENT-GUARD", 9301L, "FINORA-FINERACT-V3", now));
        assertThat(noteIssuanceService.finalizeCommitmentsAfterDisbursement(listing.listingId())).isZero();
        assertThat(noteIssuanceService.activateNotesAfterDisbursement(listing.listingId())).isZero();
        assertThat(noteRepository.findByLoanIdAndStatus(stored.getLoanId(), NoteStatus.ACTIVE)).hasSize(2);
    }

    @Test
    @DisplayName("Danh mục giữ khoản vay đã tất toán: không mất vị thế và tổng tiền đã nhận")
    void portfolioKeepsSettledPositionsAndReceivedTotals() {
        actAs("INVESTOR-SETTLED");
        MarketListingResponse listingResponse = newListing("2000000.00", "1000000.00");
        fundingService.placeOrder(listingResponse.listingId(), "key-settled",
                new PlaceOrderRequest(new BigDecimal("2000000.00"), "INVESTOR_RISK_V1"));
        var listing = listingRepository.findById(listingResponse.listingId()).orElseThrow();
        Instant now = Instant.parse("2026-10-17T03:00:00Z");
        loanDisbursedEventHandler.handle(UUID.randomUUID(), now, new LoanDisbursedEventData(
                UUID.randomUUID(), listing.getLoanId(), listing.getApplicationNumber(), "LC-SETTLED-" + listing.getLoanId(),
                listing.getId(), "2000000.00", "VND", "MOCK-PAYMENT-SETTLED", 9201L, "FINORA-FINERACT-V3", now));
        // Mô phỏng kết quả tất toán: gốc thu hết, Note đóng (luồng thật đi qua RepaymentDistributed).
        noteRepository.findByLoanIdAndStatus(listing.getLoanId(), NoteStatus.ACTIVE).forEach(note -> {
            note.setPrincipalRepaid(note.getPrincipalAmount());
            note.setOutstandingPrincipal(BigDecimal.ZERO.setScale(2));
            note.setInterestReceived(new BigDecimal("50000.00"));
            note.setStatus(NoteStatus.CLOSED);
            noteRepository.save(note);
        });

        actAs("INVESTOR-SETTLED");
        var portfolio = portfolioService.portfolio();

        assertThat(portfolio.activeNoteCount()).isZero();
        assertThat(portfolio.positions()).singleElement().satisfies(position -> {
            assertThat(position.loanId()).isEqualTo(listing.getLoanId());
            assertThat(position.status()).isEqualTo("CLOSED");
            assertThat(position.outstandingPrincipal()).isEqualTo("0.00");
        });
        assertThat(new BigDecimal(portfolio.principalRepaid())).isEqualByComparingTo("2000000.00");
        assertThat(new BigDecimal(portfolio.interestReceived())).isEqualByComparingTo("100000.00");
        assertThat(portfolio.weightedAverageRate()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("Allocation đã gọi đủ vốn bị khóa và không thể hủy trực tiếp")
    void fullyFundedAllocationCannotBeCancelledDirectly() {
        actAs("INVESTOR-CANCEL");
        MarketListingResponse listing = newListing("3000000.00", "1000000.00");

        OrderResponse order = fundingService.placeOrder(listing.listingId(), "key-cancel",
                new PlaceOrderRequest(new BigDecimal("3000000.00"), "INVESTOR_RISK_V1"));

        assertThat(listingRepository.findById(listing.listingId()).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.FULLY_FUNDED);

        assertThatThrownBy(() -> fundingService.cancelOrder(order.orderReference()))
                .isInstanceOf(InvestmentDomainException.class)
                .hasMessageContaining("khóa");

        var saved = listingRepository.findById(listing.listingId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ListingStatus.FULLY_FUNDED);
        assertThat(saved.getCommittedAmount()).isEqualByComparingTo("3000000.00");
    }

    @Test
    @DisplayName("Đã khóa vốn để giải ngân thì không hủy lệnh được nữa")
    void cannotCancelAfterFinalize() {
        actAs("INVESTOR-LOCK");
        MarketListingResponse listing = newListing("2000000.00", "1000000.00");

        OrderResponse order = fundingService.placeOrder(listing.listingId(), "key-lock",
                new PlaceOrderRequest(new BigDecimal("2000000.00"), "INVESTOR_RISK_V1"));
        noteIssuanceService.finalizeCommitments(listing.listingId());

        assertThatThrownBy(() -> fundingService.cancelOrder(order.orderReference()))
                .isInstanceOf(InvestmentDomainException.class)
                .hasMessageContaining("giải ngân");

        assertThat(commitmentRepository.findAll()).anySatisfy(commitment ->
                assertThat(commitment.getStatus()).isEqualTo(CommitmentStatus.FINALIZED));
    }

    @Test
    @DisplayName("Số tiền không chia hết cho mệnh giá Note bị từ chối trước khi giữ tiền")
    void rejectsIndivisibleAmountBeforeHoldingMoney() {
        actAs("INVESTOR-DIV");
        MarketListingResponse listing = newListing("10000000.00", "1000000.00");

        assertThatThrownBy(() -> fundingService.placeOrder(listing.listingId(), "key-div",
                new PlaceOrderRequest(new BigDecimal("1500000.00"), "INVESTOR_RISK_V1")))
                .isInstanceOf(InvestmentDomainException.class);

        assertThat(listingRepository.findById(listing.listingId()).orElseThrow()
                .getCommittedAmount()).isEqualByComparingTo("0.00");
    }
    @Test
    @DisplayName("Phần còn thiếu nhỏ hơn mức tối thiểu vẫn lấp được đúng phần đó, không lấp lẻ hơn")
    void fillsRemainderBelowMinimum() {
        MarketListingResponse created = newListing("10000000.00", "1000000.00");
        // Mệnh giá chốt lúc tạo listing (updatable=false): ghi thẳng cột để dựng mệnh giá < mức tối thiểu.
        jdbcTemplate.update("UPDATE market_listings SET note_denomination = 100000.00 WHERE id = ?",
                created.listingId());

        actAs("INVESTOR-REM-A");
        fundingService.placeOrder(created.listingId(), "key-rem-a", new PlaceOrderRequest(new BigDecimal("9500000.00"), "INVESTOR_RISK_V1"));

        actAs("INVESTOR-REM-B");
        assertThatThrownBy(() -> fundingService.placeOrder(created.listingId(), "key-rem-b1",
                new PlaceOrderRequest(new BigDecimal("400000.00"), "INVESTOR_RISK_V1")))
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("ORDER_BELOW_MINIMUM");
        OrderResponse last = fundingService.placeOrder(created.listingId(), "key-rem-b2",
                new PlaceOrderRequest(new BigDecimal("500000.00"), "INVESTOR_RISK_V1"));

        assertThat(last.status()).isEqualTo("COMMITTED");
        assertThat(listingRepository.findById(created.listingId()).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.FULLY_FUNDED);
    }

    @Test
    @DisplayName("Hết hạn gọi vốn: huỷ phần vốn, báo Loan, worker nhả tiền giữ về ví")
    void expiredListingCancelsCommitmentsAndReleasesHolds() {
        MarketListingResponse created = newListing("10000000.00", "1000000.00");
        actAs("INVESTOR-EXP");
        BigDecimal before = stubPayment().availableBalance("INVESTOR-EXP");
        OrderResponse order = fundingService.placeOrder(created.listingId(), "key-exp",
                new PlaceOrderRequest(new BigDecimal("3000000.00"), "INVESTOR_RISK_V1"));
        assertThat(stubPayment().availableBalance("INVESTOR-EXP")).isEqualByComparingTo(before.subtract(new BigDecimal("3000000")));

        jdbcTemplate.update("UPDATE market_listings SET funding_closes_at = now() - interval '1 minute' WHERE id = ?",
                created.listingId());
        SecurityContextHolder.clearContext();

        assertThat(listingService.closeExpiredListings(100)).isGreaterThanOrEqualTo(1);

        var closed = listingRepository.findById(created.listingId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(ListingStatus.CLOSED);
        assertThat(closed.getCommittedAmount()).isEqualByComparingTo("0.00");
        assertThat(commitmentRepository.findByListingIdAndStatus(created.listingId(), CommitmentStatus.CANCELLED))
                .hasSize(1);
        var cancelled = orderRepository.findByOrderReference(order.orderReference()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getPaymentReleasedAt()).isNull();
        assertThat(outboxRepository.findAll()).anySatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo("LoanFundingExpired");
            assertThat(event.getAggregateId()).isEqualTo(closed.getApplicationNumber());
            assertThat(event.getPayloadJson()).contains("FUNDING_WINDOW_EXPIRED").contains("3000000.00");
        });

        assertThat(fundingMaintenance.releasePendingHolds(100)).isGreaterThanOrEqualTo(1);
        assertThat(orderRepository.findByOrderReference(order.orderReference()).orElseThrow()
                .getPaymentReleasedAt()).isNotNull();
        assertThat(stubPayment().availableBalance("INVESTOR-EXP")).isEqualByComparingTo(before);
        // Quét lại không nhả lần hai.
        assertThat(orderRepository.findReleasePending(org.springframework.data.domain.PageRequest.of(0, 100)))
                .noneMatch(item -> item.getOrderReference().equals(order.orderReference()));
    }

    @Test
    @DisplayName("Đủ vốn nhưng Loan hủy (hợp đồng hết hạn): listing CANCELLED, worker trả tiền giữ đúng một lần")
    void loanFundingCancellationReleasesHeldMoneyExactlyOnce() {
        MarketListingResponse created = newListing("10000000.00", "1000000.00");
        actAs("INVESTOR-CANCEL");
        BigDecimal before = stubPayment().availableBalance("INVESTOR-CANCEL");
        OrderResponse order = fundingService.placeOrder(created.listingId(), "key-cancel-funded",
                new PlaceOrderRequest(new BigDecimal("10000000.00"), "INVESTOR_RISK_V1"));
        SecurityContextHolder.clearContext();
        var funded = listingRepository.findById(created.listingId()).orElseThrow();
        assertThat(funded.getStatus()).isEqualTo(ListingStatus.FULLY_FUNDED);

        UUID eventId = UUID.randomUUID();
        LoanFundingCancelledEventData event = cancellationOf(funded, funded.getAllocationHash(), "CONTRACT_EXPIRED");
        loanFundingCancelledHandler.handle(eventId, Instant.now(), event);
        // Kafka giao trùng cùng eventId và một bản phát lại với eventId khác đều không hủy/nhả tiền lần hai.
        loanFundingCancelledHandler.handle(eventId, Instant.now(), event);
        loanFundingCancelledHandler.handle(UUID.randomUUID(), Instant.now(), event);

        var cancelled = listingRepository.findById(created.listingId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(ListingStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo("CONTRACT_EXPIRED");
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(cancelled.getCommittedAmount()).isEqualByComparingTo("0.00");
        assertThat(commitmentRepository.findByListingIdAndStatus(created.listingId(), CommitmentStatus.CANCELLED))
                .hasSize(1);
        var cancelledOrder = orderRepository.findByOrderReference(order.orderReference()).orElseThrow();
        assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelledOrder.getRejectedReasonCode()).isEqualTo("LOAN_FUNDING_CANCELLED");
        // API market trả đúng mã lý do để app/web giải thích vì sao đợt gọi vốn bị hủy.
        MarketListingResponse view = listingService.detail(created.listingId());
        assertThat(view.status()).isEqualTo("CANCELLED");
        assertThat(view.cancellationReason()).isEqualTo("CONTRACT_EXPIRED");
        assertThat(view.cancelledAt()).isNotNull();

        assertThat(fundingMaintenance.releasePendingHolds(100)).isGreaterThanOrEqualTo(1);
        assertThat(stubPayment().availableBalance("INVESTOR-CANCEL")).isEqualByComparingTo(before);
        assertThat(orderRepository.findReleasePending(org.springframework.data.domain.PageRequest.of(0, 100)))
                .noneMatch(item -> item.getOrderReference().equals(order.orderReference()));
    }

    @Test
    @DisplayName("Yêu cầu hủy không khớp allocation đã khóa bị từ chối, tiền vẫn được giữ")
    void loanFundingCancellationRejectsMismatchedAllocation() {
        MarketListingResponse created = newListing("10000000.00", "1000000.00");
        actAs("INVESTOR-CANCEL-MISMATCH");
        fundingService.placeOrder(created.listingId(), "key-cancel-mismatch",
                new PlaceOrderRequest(new BigDecimal("10000000.00"), "INVESTOR_RISK_V1"));
        SecurityContextHolder.clearContext();
        var funded = listingRepository.findById(created.listingId()).orElseThrow();

        assertThatThrownBy(() -> loanFundingCancelledHandler.handle(UUID.randomUUID(), Instant.now(),
                cancellationOf(funded, "f".repeat(64), "CONTRACT_DECLINED")))
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("FUNDING_CANCELLATION_MISMATCH");
        assertThat(listingRepository.findById(created.listingId()).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.FULLY_FUNDED);
        assertThat(commitmentRepository.findByListingIdAndStatus(created.listingId(), CommitmentStatus.ACTIVE))
                .hasSize(1);
    }

    @Test
    @DisplayName("Phần vốn đã khóa để giải ngân thì không được hủy gọi vốn (tránh trả tiền hai lần)")
    void loanFundingCancellationRefusedAfterCommitmentsFinalized() {
        MarketListingResponse created = newListing("10000000.00", "1000000.00");
        actAs("INVESTOR-CANCEL-FINAL");
        fundingService.placeOrder(created.listingId(), "key-cancel-final",
                new PlaceOrderRequest(new BigDecimal("10000000.00"), "INVESTOR_RISK_V1"));
        SecurityContextHolder.clearContext();
        var funded = listingRepository.findById(created.listingId()).orElseThrow();
        noteIssuanceService.finalizeCommitments(created.listingId());

        assertThatThrownBy(() -> loanFundingCancelledHandler.handle(UUID.randomUUID(), Instant.now(),
                cancellationOf(funded, funded.getAllocationHash(), "DISBURSEMENT_FAILED")))
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("FUNDING_ALREADY_DISBURSED");
        assertThat(listingRepository.findById(created.listingId()).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.FULLY_FUNDED);
    }

    @Test
    @DisplayName("LoanFundingRequested có mục đích gồm nhãn và 500 ký tự chi tiết vẫn tạo được listing")
    void acceptsLongBorrowerPurposeFromLoan() {
        long loanId = LOAN_SEQUENCE.incrementAndGet();
        // Đúng cách Loan ghép: nhãn LoanPurpose + ": " + chi tiết người vay tự nhập (tối đa 500 ký tự).
        String purpose = "Mục đích khác: " + "x".repeat(500);
        loanFundingRequestedHandler.handle(UUID.randomUUID(), Instant.now(), new LoanFundingRequestedEventData(
                loanId, "LA-TEST-" + loanId, 1, 1, "SP-01", purpose, null, "B", 700,
                "10000000.00", "15.0000", 12, "ANNUITY", "LOAN_TERMS_V1", "a".repeat(64), "BORROWER-LONG"));

        var listing = listingRepository.findAll().stream()
                .filter(item -> item.getLoanId().equals(loanId)).findFirst().orElseThrow();
        assertThat(listing.getPurpose()).isEqualTo(purpose);
        assertThat(listing.getRegion()).isEqualTo("Không công bố");
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.OPEN);
    }

    private LoanFundingCancelledEventData cancellationOf(
            com.finora.investment.domain.listing.MarketListing listing, String allocationHash, String reasonCode) {
        return new LoanFundingCancelledEventData(
                listing.getLoanId(), listing.getApplicationNumber(), listing.getId(), listing.getFundingRound(),
                "LC-CANCEL-" + listing.getId(), listing.getAllocationVersion(), allocationHash,
                reasonCode, Instant.now());
    }

    @Test
    @DisplayName("Lệnh kẹt chờ giữ tiền quá hạn được worker làm tiếp tới cam kết")
    void stalePendingOrderIsResumed() {
        MarketListingResponse created = newListing("10000000.00", "1000000.00");
        var pending = orderTransactions.createPendingOrder(created.listingId(), "INVESTOR-STALE",
                "key-stale", "hash-stale", new PlaceOrderRequest(new BigDecimal("2000000.00")));
        pending.setUpdatedAt(Instant.now().minusSeconds(600));
        orderRepository.save(pending);

        assertThat(fundingMaintenance.resumeStalePendingOrders(100)).isGreaterThanOrEqualTo(1);

        assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMMITTED);
        assertThat(listingRepository.findById(created.listingId()).orElseThrow().getCommittedAmount())
                .isEqualByComparingTo("2000000.00");
    }

    @Test
    @DisplayName("R3: lệnh chờ mà Payment đã giữ tiền được chốt theo khoản giữ đó, không giữ lần hai")
    void pendingOrderWhoseHoldExistsIsCommittedWithoutHoldingTwice() {
        MarketListingResponse created = newListing("10000000.00", "1000000.00");
        BigDecimal before = stubPayment().availableBalance("INVESTOR-R3-HELD");
        var pending = orderTransactions.createPendingOrder(created.listingId(), "INVESTOR-R3-HELD",
                "key-r3-held", "hash-r3-held", new PlaceOrderRequest(new BigDecimal("2000000.00")));
        // Lần giữ tiền trước đã thành công ở Payment nhưng Investment mất kết quả.
        assertThat(stubPayment().hold("INVESTOR-R3-HELD", new BigDecimal("2000000.00"), pending.getOrderReference())
                .isSuccess()).isTrue();
        pending.setUpdatedAt(Instant.now().minusSeconds(600));
        orderRepository.save(pending);

        assertThat(fundingMaintenance.resumeStalePendingOrders(100)).isGreaterThanOrEqualTo(1);

        var committed = orderRepository.findById(pending.getId()).orElseThrow();
        assertThat(committed.getStatus()).isEqualTo(OrderStatus.COMMITTED);
        assertThat(committed.getPaymentHoldReference()).isEqualTo(pending.getOrderReference());
        assertThat(stubPayment().availableBalance("INVESTOR-R3-HELD"))
                .isEqualByComparingTo(before.subtract(new BigDecimal("2000000.00")));
    }

    @Test
    @DisplayName("R3: lệnh chờ có khoản giữ đã được nhả bị từ chối, không cam kết trên khoản không còn tiền")
    void pendingOrderWhoseHoldWasAlreadyReleasedIsRejectedNotCommitted() {
        MarketListingResponse created = newListing("10000000.00", "1000000.00");
        var pending = orderTransactions.createPendingOrder(created.listingId(), "INVESTOR-R3-RELEASED",
                "key-r3-released", "hash-r3-released", new PlaceOrderRequest(new BigDecimal("2000000.00")));
        stubPayment().hold("INVESTOR-R3-RELEASED", new BigDecimal("2000000.00"), pending.getOrderReference());
        stubPayment().release(pending.getOrderReference(), pending.getOrderReference());
        BigDecimal before = stubPayment().availableBalance("INVESTOR-R3-RELEASED");
        pending.setUpdatedAt(Instant.now().minusSeconds(600));
        orderRepository.save(pending);

        fundingMaintenance.resumeStalePendingOrders(100);

        var rejected = orderRepository.findById(pending.getId()).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(rejected.getRejectedReasonCode()).isEqualTo("PAYMENT_HOLD_ALREADY_RELEASED");
        assertThat(rejected.getPaymentReleasedAt()).isNotNull();
        assertThat(listingRepository.findById(created.listingId()).orElseThrow().getCommittedAmount())
                .isEqualByComparingTo("0.00");
        assertThat(stubPayment().availableBalance("INVESTOR-R3-RELEASED")).isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("R3: người dùng hủy lệnh thì tiền giữ được nhả sau khi lệnh đã hủy xong")
    void userCancelReleasesTheHoldAfterTheOrderIsCancelled() {
        actAs("INVESTOR-R3-CANCEL");
        MarketListingResponse created = newListing("10000000.00", "1000000.00");
        BigDecimal before = stubPayment().availableBalance("INVESTOR-R3-CANCEL");
        OrderResponse order = fundingService.placeOrder(created.listingId(), "key-r3-cancel",
                new PlaceOrderRequest(new BigDecimal("2000000.00"), "INVESTOR_RISK_V1"));

        fundingService.cancelOrder(order.orderReference());

        var cancelled = orderRepository.findByOrderReference(order.orderReference()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getPaymentReleasedAt()).isNotNull();
        assertThat(stubPayment().availableBalance("INVESTOR-R3-CANCEL")).isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("R3: Payment từ chối nhả chắc chắn thì dừng cho vận hành; admin hẹn thử lại thì worker nhả được")
    void releaseThatPaymentRefusesStopsForOperationsAndCanBeRetried() {
        actAs("INVESTOR-R3-BLOCKED");
        MarketListingResponse created = newListing("10000000.00", "1000000.00");
        BigDecimal before = stubPayment().availableBalance("INVESTOR-R3-BLOCKED");
        OrderResponse order = fundingService.placeOrder(created.listingId(), "key-r3-blocked",
                new PlaceOrderRequest(new BigDecimal("2000000.00"), "INVESTOR_RISK_V1"));
        stubPayment().failNextRelease("PAYMENT_HOLD_NOT_RELEASABLE", false);

        fundingService.cancelOrder(order.orderReference());

        var blocked = orderRepository.findByOrderReference(order.orderReference()).orElseThrow();
        assertThat(blocked.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(blocked.getPaymentReleasedAt()).isNull();
        assertThat(blocked.getReleaseBlockedAt()).isNotNull();
        assertThat(blocked.getReleaseErrorCode()).isEqualTo("PAYMENT_HOLD_NOT_RELEASABLE");
        // Worker không quét lệnh đã dừng; vận hành thấy lệnh trên màn sự cố.
        assertThat(orderRepository.findReleaseDue(Instant.now(), org.springframework.data.domain.PageRequest.of(0, 100)))
                .noneMatch(item -> item.getOrderReference().equals(order.orderReference()));
        assertThat(orderIncidentService.list(0, 100).getContent()).anySatisfy(incident -> {
            assertThat(incident.orderReference()).isEqualTo(order.orderReference());
            assertThat(incident.issue()).isEqualTo("RELEASE_BLOCKED");
        });

        orderIncidentService.retry(order.orderReference());
        assertThat(fundingMaintenance.releasePendingHolds(100)).isGreaterThanOrEqualTo(1);

        assertThat(orderRepository.findByOrderReference(order.orderReference()).orElseThrow().getPaymentReleasedAt())
                .isNotNull();
        assertThat(stubPayment().availableBalance("INVESTOR-R3-BLOCKED")).isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("R3: lỗi tạm thời khi nhả tiền lùi lịch thử lại thay vì chặn hàng đợi")
    void retryableReleaseFailureBacksOffInsteadOfBlockingTheQueue() {
        actAs("INVESTOR-R3-BACKOFF");
        MarketListingResponse created = newListing("10000000.00", "1000000.00");
        OrderResponse order = fundingService.placeOrder(created.listingId(), "key-r3-backoff",
                new PlaceOrderRequest(new BigDecimal("2000000.00"), "INVESTOR_RISK_V1"));
        stubPayment().failNextRelease("PAYMENT_UNAVAILABLE", true);

        fundingService.cancelOrder(order.orderReference());

        var waiting = orderRepository.findByOrderReference(order.orderReference()).orElseThrow();
        assertThat(waiting.getPaymentReleasedAt()).isNull();
        assertThat(waiting.getReleaseBlockedAt()).isNull();
        assertThat(waiting.getReleaseAttemptCount()).isEqualTo(1);
        assertThat(waiting.getReleaseNextAttemptAt()).isAfter(Instant.now());
        assertThat(orderRepository.findReleaseDue(Instant.now(), org.springframework.data.domain.PageRequest.of(0, 100)))
                .noneMatch(item -> item.getOrderReference().equals(order.orderReference()));
        assertThat(orderRepository.findReleaseDue(Instant.now().plusSeconds(3600),
                org.springframework.data.domain.PageRequest.of(0, 100)))
                .anyMatch(item -> item.getOrderReference().equals(order.orderReference()));
    }

    @Test
    @DisplayName("Người vay không đặt vốn được vào khoản vay của chính mình")
    void borrowerCannotFundOwnLoan() {
        MarketListingResponse created = newListing("10000000.00", "1000000.00");
        // borrower_id là updatable=false nên ghi thẳng cột để dựng dữ liệu kiểm thử.
        jdbcTemplate.update("UPDATE market_listings SET borrower_id = ? WHERE id = ?",
                "BORROWER-SELF", created.listingId());

        actAs("BORROWER-SELF");
        assertThatThrownBy(() -> fundingService.placeOrder(created.listingId(), "key-self",
                new PlaceOrderRequest(new BigDecimal("1000000.00"), "INVESTOR_RISK_V1")))
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("SELF_FUNDING_FORBIDDEN");
    }

    private StubPaymentClient stubPayment() {
        return (StubPaymentClient) paymentClient;
    }
}
