package com.finora.investment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.document.pades.PadesSignatureVerifier;
import com.finora.investment.domain.orderbook.OrderSide;
import com.finora.investment.domain.transferdocument.NoteTransferDocument;
import com.finora.investment.domain.transferdocument.NoteTransferDocumentStatus;
import com.finora.investment.dto.request.CreateListingRequest;
import com.finora.investment.dto.request.PlaceBookOrderRequest;
import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.NoteTransferDocumentRepository;
import com.finora.investment.service.orderbook.OrderBookCommandService;
import com.finora.investment.service.orderbook.OrderBookQueryService;
import com.finora.investment.service.orderbook.OrderBookSettlementService;
import com.finora.investment.service.transferdocument.TransferDocumentAdminService;
import com.finora.investment.service.transferdocument.TransferDocumentIssuer;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Luồng thật trên PostgreSQL: gọi vốn → phát hành Note → khớp lệnh trên sổ → thanh toán → giấy xác nhận chuyển
 * nhượng được lập, đóng dấu và ghi event neo hash. Kèm kiểm danh sách đầu tư ẩn khoản vay của chính người xem.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "finora.investment.payment.mode=stub",
        "finora.investment.user.mode=stub",
        "finora.investment.auto-invest.enabled=false",
        "finora.investment.order-book.settlement.enabled=false",
        "finora.investment.transfer-document.enabled=false",
        "finora.investment.maintenance.enabled=false",
        "finora.investment.outbox.publisher-delay=3600000"
})
@Testcontainers
class TransferDocumentFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_investment_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    private static final AtomicInteger LOAN_SEQUENCE = new AtomicInteger(5000);

    @Autowired
    private MarketListingService listingService;

    @Autowired
    private FundingService fundingService;

    @Autowired
    private NoteIssuanceService noteIssuanceService;

    @Autowired
    private OrderBookCommandService orderBookCommands;

    @Autowired
    private OrderBookQueryService orderBookQueries;

    @Autowired
    private OrderBookSettlementService settlementService;

    @Autowired
    private TransferDocumentIssuer issuer;

    @Autowired
    private TransferDocumentAdminService adminService;

    @Autowired
    private NoteTransferDocumentRepository documentRepository;

    @Autowired
    private BookTradeRepository tradeRepository;

    @Autowired
    private PadesSignatureVerifier verifier;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Khớp lệnh đã thanh toán có giấy xác nhận đóng dấu số và event neo đúng hash của PDF")
    void settledTradeGetsSealedDocumentAndAnchorEvent() throws Exception {
        Long listingId = listingWithNotes("BORROWER-DOC", "SELLER-DOC");

        actAs("SELLER-DOC");
        orderBookCommands.placeOrder(listingId, "ask-doc",
                new PlaceBookOrderRequest(OrderSide.ASK, new BigDecimal("99.0"), 2, null));
        actAs("BUYER-DOC");
        orderBookCommands.placeOrder(listingId, "bid-doc",
                new PlaceBookOrderRequest(OrderSide.BID, new BigDecimal("99.0"), 2, null));
        SecurityContextHolder.clearContext();

        String tradeReference = tradeRepository.findAll().stream()
                .filter(trade -> trade.getListingId().equals(listingId))
                .findFirst().orElseThrow().getTradeReference();

        // Chưa thanh toán thì chưa lập văn bản.
        issuer.issueDue(20);
        assertThat(documentRepository.findByTradeReference(tradeReference)).isEmpty();

        assertThat(settlementService.settleDue(20)).isPositive();
        issuer.issueDue(20);
        // Lượt quét lặp lại không lập văn bản thứ hai.
        issuer.issueDue(20);

        NoteTransferDocument document = documentRepository.findByTradeReference(tradeReference).orElseThrow();
        assertThat(document.getStatus()).isEqualTo(NoteTransferDocumentStatus.ISSUED);
        assertThat(document.getDocumentNumber()).isEqualTo("CN-" + tradeReference);

        TransferDocumentAdminService.IssuedPdf pdf = adminService.pdf(tradeReference);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf.content()));
        assertThat(document.getContentHash()).isEqualTo(hash);
        assertThat(verifier.verify(pdf.content())).hasSize(1);

        JsonNode artifact = adminService.detail(tradeReference).proofArtifact();
        assertThat(artifact.path("documentHash").asText()).isEqualTo(hash);
        assertThat(artifact.path("tradeReference").asText()).isEqualTo(tradeReference);
        assertThat(artifact.path("hashAlgorithm").asText()).isEqualTo("SHA-256");

        List<Map<String, Object>> events = jdbcTemplate.queryForList("""
                SELECT payload_json::text AS payload_json FROM investment_outbox_events
                WHERE event_type = 'NoteTransferDocumentIssued' AND payload_json::text LIKE ?
                """, "%" + tradeReference + "%");
        assertThat(events).hasSize(1);
        assertThat(objectMapper.readTree(events.get(0).get("payload_json").toString())).isEqualTo(artifact);
    }

    @Test
    @DisplayName("Danh sách Gọi vốn và Sàn không hiện khoản vay mà người xem là người vay")
    void investmentListsHideViewersOwnLoans() {
        MarketListingResponse open = newListing("BORROWER-HIDE", "3000000.00");
        Long tradable = listingWithNotes("BORROWER-HIDE", "SELLER-HIDE");

        assertThat(listingIds(listingService.search(null, null, null, null, "BORROWER-HIDE", 0, 100).getContent()))
                .doesNotContain(open.listingId());
        assertThat(listingIds(listingService.search(null, null, null, null, "SOMEONE-ELSE", 0, 100).getContent()))
                .contains(open.listingId());
        assertThat(listingIds(listingService.search(null, null, null, null, null, 0, 100).getContent()))
                .contains(open.listingId());

        assertThat(orderBookQueries.listBooks("BORROWER-HIDE", PageRequest.of(0, 100)).getContent())
                .noneMatch(book -> book.listingId().equals(tradable));
        assertThat(orderBookQueries.listBooks("SOMEONE-ELSE", PageRequest.of(0, 100)).getContent())
                .anyMatch(book -> book.listingId().equals(tradable));
    }

    /** Listing gọi đủ vốn, một nhà đầu tư nắm 3 Note đang lưu hành. */
    private Long listingWithNotes(String borrowerId, String holderId) {
        MarketListingResponse listing = newListing(borrowerId, "3000000.00");
        actAs(holderId);
        fundingService.placeOrder(listing.listingId(), "fund-" + listing.listingId(),
                new PlaceOrderRequest(new BigDecimal("3000000.00"), "INVESTOR_RISK_V1"));
        SecurityContextHolder.clearContext();
        noteIssuanceService.finalizeCommitments(listing.listingId());
        noteIssuanceService.activateNotes(listing.listingId());
        return listing.listingId();
    }

    private MarketListingResponse newListing(String borrowerId, String target) {
        actAs("ADMIN-DOC", "ROLE_ADMIN");
        try {
            long loanId = LOAN_SEQUENCE.incrementAndGet();
            return listingService.createListingFrom(CreateListingRequest.builder()
                    .loanId(loanId)
                    .applicationNumber("LA-DOC-" + loanId)
                    .borrowerId(borrowerId)
                    .listingVersion(1)
                    .fundingRound(1)
                    .termsVersion("LOAN_TERMS_V1")
                    .termsHash("a".repeat(64))
                    .productCode("SP-01")
                    .purpose("Vốn kinh doanh")
                    .region("TP.HCM")
                    .creditGrade("B")
                    .creditScore(700)
                    .targetAmount(new BigDecimal(target))
                    .annualInterestRate(new BigDecimal("15.0000"))
                    .termMonths(12)
                    .repaymentMethod("ANNUITY")
                    .build());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static List<Long> listingIds(List<MarketListingResponse> listings) {
        return listings.stream().map(MarketListingResponse::listingId).toList();
    }

    private void actAs(String userId) {
        actAs(userId, "ROLE_INVESTOR");
    }

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
}
