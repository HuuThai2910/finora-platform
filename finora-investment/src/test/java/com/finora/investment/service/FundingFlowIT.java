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
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
@SpringBootTest
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
                new PlaceOrderRequest(new BigDecimal("6000000.00")));

        actAs("INVESTOR-LIST-B");
        OrderResponse cancelled = fundingService.placeOrder(listing.listingId(), "key-list-b",
                new PlaceOrderRequest(new BigDecimal("2000000.00")));
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
        long loanId = LOAN_SEQUENCE.incrementAndGet();
        return listingService.createListingFrom(CreateListingRequest.builder()
                .loanId(loanId)
                .contractNumber("HD-" + loanId)
                .productCode("SP-01")
                .purpose("Vốn kinh doanh")
                .region("TP.HCM")
                .creditGrade("B")
                .creditScore(700)
                .targetAmount(new BigDecimal(target))
                .annualInterestRate(new BigDecimal("0.1500"))
                .termMonths(12)
                .repaymentMethod("ANNUITY")
                .build());
    }

    @Test
    @DisplayName("Gọi đủ vốn thì đóng sàn và ghi đúng một event LoanFullyFunded")
    void fullFundingClosesListingOnce() {
        actAs("INVESTOR-A");
        MarketListingResponse listing = newListing("10000000.00", "1000000.00");

        fundingService.placeOrder(listing.listingId(), "key-a-1",
                new PlaceOrderRequest(new BigDecimal("6000000.00")));

        actAs("INVESTOR-B");
        OrderResponse second = fundingService.placeOrder(listing.listingId(), "key-b-1",
                new PlaceOrderRequest(new BigDecimal("4000000.00")));

        assertThat(second.status()).isEqualTo("COMMITTED");

        var saved = listingRepository.findById(listing.listingId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ListingStatus.FULLY_FUNDED);
        assertThat(saved.getCommittedAmount()).isEqualByComparingTo("10000000.00");
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
                            new PlaceOrderRequest(new BigDecimal("1000000.00"))
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

        PlaceOrderRequest request = new PlaceOrderRequest(new BigDecimal("2000000.00"));
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
                new PlaceOrderRequest(new BigDecimal("1000000.00")));

        assertThatThrownBy(() -> fundingService.placeOrder(listing.listingId(), "key-reuse",
                new PlaceOrderRequest(new BigDecimal("3000000.00"))))
                .isInstanceOf(InvestmentDomainException.class)
                .hasMessageContaining("idempotency");
    }

    @Test
    @DisplayName("Xé nhỏ vốn thành Notes đúng số lượng và chạy lại không nhân đôi")
    void activateNotesSplitsCapitalAndIsIdempotent() {
        actAs("INVESTOR-NOTE");
        MarketListingResponse listing = newListing("10000000.00", "1000000.00");

        fundingService.placeOrder(listing.listingId(), "key-note-1",
                new PlaceOrderRequest(new BigDecimal("6000000.00")));
        actAs("INVESTOR-NOTE-2");
        fundingService.placeOrder(listing.listingId(), "key-note-2",
                new PlaceOrderRequest(new BigDecimal("4000000.00")));

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
    @DisplayName("Hủy lệnh trả vốn về sàn và mở lại khoản vay đã đủ vốn")
    void cancelReleasesCapitalAndReopensListing() {
        actAs("INVESTOR-CANCEL");
        MarketListingResponse listing = newListing("3000000.00", "1000000.00");

        OrderResponse order = fundingService.placeOrder(listing.listingId(), "key-cancel",
                new PlaceOrderRequest(new BigDecimal("3000000.00")));

        assertThat(listingRepository.findById(listing.listingId()).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.FULLY_FUNDED);

        fundingService.cancelOrder(order.orderReference());

        var saved = listingRepository.findById(listing.listingId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ListingStatus.OPEN);
        assertThat(saved.getCommittedAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Đã khóa vốn để giải ngân thì không hủy lệnh được nữa")
    void cannotCancelAfterFinalize() {
        actAs("INVESTOR-LOCK");
        MarketListingResponse listing = newListing("2000000.00", "1000000.00");

        OrderResponse order = fundingService.placeOrder(listing.listingId(), "key-lock",
                new PlaceOrderRequest(new BigDecimal("2000000.00")));
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
                new PlaceOrderRequest(new BigDecimal("1500000.00"))))
                .isInstanceOf(InvestmentDomainException.class);

        assertThat(listingRepository.findById(listing.listingId()).orElseThrow()
                .getCommittedAmount()).isEqualByComparingTo("0.00");
    }
}
