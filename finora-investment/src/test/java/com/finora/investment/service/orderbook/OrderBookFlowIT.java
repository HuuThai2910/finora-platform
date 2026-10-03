package com.finora.investment.service.orderbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.impl.StubPaymentClient;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.investment.domain.orderbook.BookTrade;
import com.finora.investment.domain.orderbook.OrderSide;
import com.finora.investment.domain.orderbook.SettlementStatus;
import com.finora.investment.dto.request.CreateListingRequest;
import com.finora.investment.dto.request.PlaceBookOrderRequest;
import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.BookOrderResponse;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.dto.response.OrderBookSnapshotResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.BookOrderRepository;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.NoteTransferRepository;
import com.finora.investment.service.FundingService;
import com.finora.investment.service.MarketListingService;
import com.finora.investment.service.NoteIssuanceService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Sổ lệnh trên PostgreSQL thật: khớp giá–thời gian, đổi chủ Note, thanh toán từ tiền giữ, huỷ,
 * chặn tự khớp và tranh chấp đồng thời. Worker thanh toán tắt để test tự gọi từng bước.
 *
 * <p>Mỗi bài dùng mã nhà đầu tư riêng: ví giả lập là bean dùng chung cả context, nên số dư của bài
 * này không được lẫn sang bài khác.</p>
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "finora.investment.payment.mode=stub",
        "finora.investment.auto-invest.enabled=false",
        "finora.investment.order-book.settlement.enabled=false",
        "finora.investment.outbox.publisher-delay=3600000"
})
@Testcontainers
class OrderBookFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_investment_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    private static final AtomicInteger LOAN_SEQUENCE = new AtomicInteger(9000);

    @Autowired
    private MarketListingService listingService;
    @Autowired
    private FundingService fundingService;
    @Autowired
    private NoteIssuanceService noteIssuanceService;
    @Autowired
    private OrderBookCommandService commandService;
    @Autowired
    private OrderBookQueryService queryService;
    @Autowired
    private OrderBookSettlementService settlementService;
    @Autowired
    private BookOrderRepository orderRepository;
    @Autowired
    private BookTradeRepository tradeRepository;
    @Autowired
    private InvestmentNoteRepository noteRepository;
    @Autowired
    private NoteTransferRepository transferRepository;
    @Autowired
    private PaymentClient paymentClient;
    @Autowired
    private OrderBookAdminService adminService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(String userId, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .claim("sub", userId)
                .claim("user_id", userId)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)), userId));
    }

    private void actAs(String investorId) {
        actAs(investorId, "ROLE_INVESTOR");
    }

    private StubPaymentClient wallet() {
        return (StubPaymentClient) paymentClient;
    }

    /**
     * Một khoản vay đã giải ngân: gọi đủ vốn từ các nhà đầu tư theo số Note mỗi người, rồi phát hành
     * Note mệnh giá 1 triệu (cấu hình mặc định của V1).
     */
    private Long issuedListing(Map<String, Integer> notesByInvestor) {
        int total = notesByInvestor.values().stream().mapToInt(Integer::intValue).sum();
        actAs("ADMIN-OB", "ROLE_ADMIN");
        long loanId = LOAN_SEQUENCE.incrementAndGet();
        MarketListingResponse listing = listingService.createListingFrom(CreateListingRequest.builder()
                .loanId(loanId)
                .applicationNumber("LA-OB-" + loanId)
                .listingVersion(1)
                .fundingRound(1)
                .termsVersion("LOAN_TERMS_V1")
                .termsHash("a".repeat(64))
                .productCode("SP-01")
                .purpose("Vốn kinh doanh")
                .region("TP.HCM")
                .creditGrade("B")
                .creditScore(700)
                .targetAmount(new BigDecimal(total).multiply(new BigDecimal("1000000.00")))
                .annualInterestRate(new BigDecimal("15.0000"))
                .termMonths(12)
                .repaymentMethod("ANNUITY")
                .build());

        notesByInvestor.forEach((investor, notes) -> {
            actAs(investor);
            fundingService.placeOrder(listing.listingId(), "fund-" + loanId + "-" + investor,
                    new PlaceOrderRequest(new BigDecimal(notes).multiply(new BigDecimal("1000000.00"))));
        });
        noteIssuanceService.finalizeCommitments(listing.listingId());
        assertThat(noteIssuanceService.activateNotes(listing.listingId())).isEqualTo(total);
        SecurityContextHolder.clearContext();
        return listing.listingId();
    }

    private BookOrderResponse place(String investor, Long listingId, OrderSide side, String price, int quantity) {
        actAs(investor);
        return commandService.placeOrder(listingId, UUID.randomUUID().toString(),
                new PlaceBookOrderRequest(side, new BigDecimal(price), quantity, null));
    }

    private long notesOwnedBy(Long listingId, String investor) {
        return noteRepository.findByInvestorIdAndStatus(investor, NoteStatus.ACTIVE).stream()
                .filter(n -> n.getListingId().equals(listingId))
                .count();
    }

    private List<BookTrade> tradesOf(Long listingId) {
        Long bookId = orderRepository.findAll().stream()
                .filter(o -> o.getListingId().equals(listingId))
                .findFirst().orElseThrow().getOrderBookId();
        return tradeRepository.findByOrderBookIdOrderByExecutedAtDescIdDesc(bookId, PageRequest.of(0, 100));
    }

    @Test
    @DisplayName("Lệnh bán chạm lệnh mua nằm chờ: khớp ở giá lệnh mua, phần dư nằm lại sổ, thanh toán và nhả tiền đúng")
    void askHitsRestingBidThenSettles() {
        Long listingId = issuedListing(map("OB1-SELLER", 6, "OB1-BUYER", 4));

        BookOrderResponse bid = place("OB1-BUYER", listingId, OrderSide.BID, "97.0", 3);
        assertThat(bid.status()).isEqualTo("OPEN");
        assertThat(bid.holdAmount()).isEqualTo("2910000.00");

        BookOrderResponse ask = place("OB1-SELLER", listingId, OrderSide.ASK, "96.0", 5);
        assertThat(ask.status()).isEqualTo("PARTIALLY_FILLED");
        assertThat(ask.filledQuantity()).isEqualTo(3);

        BookTrade trade = tradesOf(listingId).get(0);
        assertThat(trade.getPricePermille()).isEqualTo(970);
        assertThat(trade.getAggressorSide()).isEqualTo(OrderSide.ASK);
        assertThat(trade.getAmount()).isEqualByComparingTo("2910000.00");
        assertThat(trade.getPlatformFee()).isEqualByComparingTo("145500.00");
        assertThat(trade.getSellerProceeds()).isEqualByComparingTo("2764500.00");
        assertThat(transferRepository.findByTradeIdOrderByNoteIdAsc(trade.getId())).hasSize(3);

        // Đổi chủ ngay khi khớp; 2 Note còn lại của lệnh bán vẫn khóa, 1 Note của người bán còn rảnh.
        assertThat(notesOwnedBy(listingId, "OB1-BUYER")).isEqualTo(7);
        assertThat(notesOwnedBy(listingId, "OB1-SELLER")).isEqualTo(3);
        actAs("OB1-SELLER");
        var position = queryService.myPosition(listingId, "OB1-SELLER");
        assertThat(position.lockedNotes()).isEqualTo(2);
        assertThat(position.freeNotes()).isEqualTo(1);

        assertThat(settlementService.settleDue(50)).isGreaterThanOrEqualTo(1);
        assertThat(tradeRepository.findById(trade.getId()).orElseThrow().getSettlementStatus())
                .isEqualTo(SettlementStatus.SETTLED);
        settlementService.releaseFinished(50);

        // Người bán: 500tr − 6tr góp vốn + 2.764.500đ. Người mua: 500tr − 4tr − 2.910.000đ.
        assertThat(wallet().availableBalance("OB1-SELLER")).isEqualByComparingTo("496764500.00");
        assertThat(wallet().availableBalance("OB1-BUYER")).isEqualByComparingTo("493090000.00");
    }

    @Test
    @DisplayName("Lệnh mua giá cao khớp ở giá bán nằm chờ, phần tiền giữ thừa được nhả")
    void bidGetsPriceImprovementAndExcessReleased() {
        Long listingId = issuedListing(map("OB2-SELLER", 2, "OB2-BUYER", 1));

        place("OB2-SELLER", listingId, OrderSide.ASK, "95.0", 2);
        BookOrderResponse bid = place("OB2-BUYER", listingId, OrderSide.BID, "99.0", 2);

        assertThat(bid.status()).isEqualTo("FILLED");
        assertThat(bid.holdAmount()).isEqualTo("1980000.00");
        assertThat(bid.holdConsumed()).isEqualTo("1900000.00");

        settlementService.settleDue(50);
        assertThat(settlementService.releaseFinished(50)).isGreaterThanOrEqualTo(1);
        // 500tr − 1tr góp vốn − 1.900.000đ khớp thật; 80.000đ giữ thừa đã về ví.
        assertThat(wallet().availableBalance("OB2-BUYER")).isEqualByComparingTo("497100000.00");
    }

    @Test
    @DisplayName("Ưu tiên giá trước, cùng giá thì lệnh vào sổ trước khớp trước")
    void pricePriorityThenTimePriority() {
        Long listingId = issuedListing(map("OB3-EARLY", 1, "OB3-LATE", 1, "OB3-CHEAP", 1, "OB3-BUYER", 1));

        BookOrderResponse early = place("OB3-EARLY", listingId, OrderSide.ASK, "98.0", 1);
        BookOrderResponse late = place("OB3-LATE", listingId, OrderSide.ASK, "98.0", 1);
        BookOrderResponse cheap = place("OB3-CHEAP", listingId, OrderSide.ASK, "97.5", 1);

        place("OB3-BUYER", listingId, OrderSide.BID, "99.0", 2);

        assertThat(orderRepository.findByOrderReference(cheap.orderReference()).orElseThrow().getFilledQuantity()).isEqualTo(1);
        assertThat(orderRepository.findByOrderReference(early.orderReference()).orElseThrow().getFilledQuantity()).isEqualTo(1);
        assertThat(orderRepository.findByOrderReference(late.orderReference()).orElseThrow().getFilledQuantity()).isZero();

        OrderBookSnapshotResponse snapshot = queryService.snapshot(listingId);
        assertThat(snapshot.bestAskPercent()).isEqualTo("98.0");
        assertThat(snapshot.asks()).hasSize(1);
        assertThat(snapshot.lastTradePercent()).isEqualTo("98.0");
        assertThat(snapshot.recentTrades()).extracting(OrderBookSnapshotResponse.TradeTick::pricePercent)
                .containsExactly("98.0", "97.5");
    }

    @Test
    @DisplayName("Lệnh mua chạm lệnh bán của chính mình bị huỷ phần còn lại, không sinh giao dịch")
    void selfTradeIsPrevented() {
        Long listingId = issuedListing(map("OB4-SELF", 2));

        BookOrderResponse ask = place("OB4-SELF", listingId, OrderSide.ASK, "95.0", 1);
        BookOrderResponse bid = place("OB4-SELF", listingId, OrderSide.BID, "99.0", 1);

        assertThat(bid.status()).isEqualTo("CANCELLED");
        assertThat(bid.cancelReason()).isEqualTo("SELF_TRADE_PREVENTED");
        assertThat(orderRepository.findByOrderReference(ask.orderReference()).orElseThrow().getStatus().name())
                .isEqualTo("OPEN");
        assertThat(tradesOf(listingId)).isEmpty();

        settlementService.releaseFinished(50);
        // Tiền giữ cho lệnh mua bị huỷ đã về đủ: chỉ còn trừ 2tr góp vốn ban đầu.
        assertThat(wallet().availableBalance("OB4-SELF")).isEqualByComparingTo("498000000.00");
    }

    @Test
    @DisplayName("Huỷ lệnh bán mở khóa Note; huỷ lại trả kết quả cũ; người khác không huỷ được; lệnh đã khớp hết không huỷ được")
    void cancelRules() {
        Long listingId = issuedListing(map("OB5-SELLER", 3, "OB5-BUYER", 1));

        BookOrderResponse ask = place("OB5-SELLER", listingId, OrderSide.ASK, "99.0", 3);
        assertThat(queryService.myPosition(listingId, "OB5-SELLER").freeNotes()).isZero();

        actAs("OB5-BUYER");
        assertThatThrownBy(() -> commandService.cancelOrder(ask.orderReference()))
                .isInstanceOf(InvestmentDomainException.class)
                .hasFieldOrPropertyWithValue("code", "ACCESS_DENIED");

        actAs("OB5-SELLER");
        BookOrderResponse cancelled = commandService.cancelOrder(ask.orderReference());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(commandService.cancelOrder(ask.orderReference()).status()).isEqualTo("CANCELLED");
        assertThat(queryService.myPosition(listingId, "OB5-SELLER").freeNotes()).isEqualTo(3);

        place("OB5-SELLER", listingId, OrderSide.ASK, "99.0", 1);
        BookOrderResponse bid = place("OB5-BUYER", listingId, OrderSide.BID, "99.0", 1);
        assertThat(bid.status()).isEqualTo("FILLED");
        actAs("OB5-BUYER");
        assertThatThrownBy(() -> commandService.cancelOrder(bid.orderReference()))
                .isInstanceOf(InvestmentDomainException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_ALREADY_FILLED");
    }

    @Test
    @DisplayName("Một Note không nằm trong hai lệnh bán; bán vượt số Note rảnh bị từ chối")
    void noteCannotBeOfferedTwice() {
        Long listingId = issuedListing(map("OB6-SELLER", 2));

        place("OB6-SELLER", listingId, OrderSide.ASK, "99.0", 2);
        assertThatThrownBy(() -> place("OB6-SELLER", listingId, OrderSide.ASK, "98.0", 1))
                .isInstanceOf(InvestmentDomainException.class)
                .hasFieldOrPropertyWithValue("code", "INSUFFICIENT_FREE_NOTES");
    }

    @Test
    @DisplayName("Gửi lại cùng khóa trả lệnh cũ; dùng lại khóa cho lệnh khác bị từ chối")
    void idempotentPlacement() {
        Long listingId = issuedListing(map("OB7-SELLER", 2, "OB7-BUYER", 1));
        actAs("OB7-BUYER");
        var request = new PlaceBookOrderRequest(OrderSide.BID, new BigDecimal("90.0"), 1, null);

        BookOrderResponse first = commandService.placeOrder(listingId, "same-key", request);
        BookOrderResponse second = commandService.placeOrder(listingId, "same-key", request);

        assertThat(second.orderReference()).isEqualTo(first.orderReference());
        // Tiền chỉ giữ một lần: 500tr − 1tr góp vốn − 900.000đ.
        assertThat(wallet().availableBalance("OB7-BUYER")).isEqualByComparingTo("498100000.00");
        assertThatThrownBy(() -> commandService.placeOrder(listingId, "same-key",
                new PlaceBookOrderRequest(OrderSide.BID, new BigDecimal("91.0"), 1, null)))
                .isInstanceOf(InvestmentDomainException.class)
                .hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    @DisplayName("Khoản vay có Note nợ xấu: đặt lệnh phải xác nhận cảnh báo")
    void defaultedBookRequiresAcknowledgement() {
        Long listingId = issuedListing(map("OB8-SELLER", 2, "OB8-BUYER", 1));
        InvestmentNote note = noteRepository.findByInvestorIdAndStatus("OB8-SELLER", NoteStatus.ACTIVE).stream()
                .filter(n -> n.getListingId().equals(listingId)).findFirst().orElseThrow();
        note.setStatus(NoteStatus.DEFAULTED);
        noteRepository.save(note);

        assertThatThrownBy(() -> place("OB8-BUYER", listingId, OrderSide.BID, "50.0", 1))
                .isInstanceOf(InvestmentDomainException.class)
                .hasFieldOrPropertyWithValue("code", "DEFAULT_NOT_ACKNOWLEDGED");

        actAs("OB8-SELLER");
        BookOrderResponse ask = commandService.placeOrder(listingId, "ack-key",
                new PlaceBookOrderRequest(OrderSide.ASK, new BigDecimal("60.0"), 1, true));
        assertThat(ask.status()).isEqualTo("OPEN");
        assertThat(queryService.snapshot(listingId).defaulted()).isTrue();
    }

    @Test
    @DisplayName("Nhiều lệnh mua cùng lúc tranh một Note: đúng một người mua được, Note chỉ bán một lần")
    void concurrentBidsForSingleNote() throws Exception {
        List<String> buyers = List.of("OB9-B1", "OB9-B2", "OB9-B3", "OB9-B4");
        Map<String, Integer> holders = new LinkedHashMap<>();
        holders.put("OB9-SELLER", 1);
        buyers.forEach(b -> holders.put(b, 1));
        Long listingId = issuedListing(holders);

        place("OB9-SELLER", listingId, OrderSide.ASK, "95.0", 1);
        SecurityContextHolder.clearContext();

        ExecutorService pool = Executors.newFixedThreadPool(buyers.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<BookOrderResponse>> results = new ArrayList<>();
            for (String buyer : buyers) {
                Callable<BookOrderResponse> task = () -> {
                    start.await();
                    try {
                        return place(buyer, listingId, OrderSide.BID, "99.0", 1);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();

            long filled = 0;
            for (Future<BookOrderResponse> result : results) {
                if ("FILLED".equals(result.get(30, TimeUnit.SECONDS).status())) {
                    filled++;
                }
            }
            assertThat(filled).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        List<BookTrade> trades = tradesOf(listingId);
        assertThat(trades).hasSize(1);
        assertThat(notesOwnedBy(listingId, "OB9-SELLER")).isZero();
        assertThat(queryService.snapshot(listingId).bids()).singleElement()
                .satisfies(level -> assertThat(level.quantity()).isEqualTo(3));
    }

    @Test
    @DisplayName("Quản trị: dải số liệu và danh sách lần khớp theo trạng thái thanh toán")
    void adminSummaryAndTrades() {
        Long listingId = issuedListing(map("OB10-SELLER", 2, "OB10-BUYER", 1));
        var before = adminService.summary();

        place("OB10-SELLER", listingId, OrderSide.ASK, "95.0", 2);
        place("OB10-BUYER", listingId, OrderSide.BID, "95.0", 1);

        var pending = adminService.summary();
        assertThat(pending.pendingCount()).isEqualTo(before.pendingCount() + 1);
        assertThat(pending.openAskNotes()).isEqualTo(before.openAskNotes() + 1);

        var pendingTrades = adminService.trades(SettlementStatus.PENDING, PageRequest.of(0, 50)).getContent();
        assertThat(pendingTrades).anySatisfy(t -> {
            assertThat(t.buyerId()).isEqualTo("OB10-BUYER");
            assertThat(t.sellerId()).isEqualTo("OB10-SELLER");
            assertThat(t.loanId()).isNotNull();
            assertThat(t.pricePercent()).isEqualTo("95.0");
        });

        settlementService.settleDue(50);
        var settled = adminService.summary();
        assertThat(settled.settledCount()).isGreaterThan(before.settledCount());
        assertThat(new BigDecimal(settled.feeCollected()))
                .isGreaterThanOrEqualTo(new BigDecimal(before.feeCollected()).add(new BigDecimal("47500.00")));
        assertThat(adminService.trades(null, PageRequest.of(0, 5)).getContent()).isNotEmpty();
    }

    private static Map<String, Integer> map(Object... pairs) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return result;
    }
}
