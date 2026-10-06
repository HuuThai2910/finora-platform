package com.finora.investment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.domain.autoinvest.AutoInvestMatch;
import com.finora.investment.dto.request.CreateListingRequest;
import com.finora.investment.dto.request.UpdateAutoInvestRequest;
import com.finora.investment.dto.response.AutoInvestMatchResponse;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.repository.AutoInvestMatchRepository;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.service.autoinvest.AutoInvestMatcher;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Auto-Invest trên PostgreSQL thật: hàng chờ ai bật trước, không khớp trùng khi chạy lại, không
 * quét ngược khoản mở trước lúc bật. Worker tắt để test gọi {@link AutoInvestMatcher} trực tiếp.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "finora.investment.payment.mode=stub",
        "finora.investment.auto-invest.enabled=false",
        "finora.investment.order-book.settlement.enabled=false",
        "finora.investment.outbox.publisher-delay=3600000"
})
@Testcontainers
class AutoInvestFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_investment_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    private static final AtomicInteger LOAN_SEQUENCE = new AtomicInteger(5000);

    @Autowired
    private AutoInvestService autoInvestService;

    @Autowired
    private AutoInvestMatcher matcher;

    @Autowired
    private MarketListingService listingService;

    @Autowired
    private MarketListingRepository listingRepository;

    @Autowired
    private AutoInvestMatchRepository matchRepository;

    @Autowired
    private InvestmentCommitmentRepository commitmentRepository;

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

    /** Mỗi bài test dùng hạng riêng để cấu hình của bài này không khớp listing của bài kia. */
    private void enableAutoInvest(String investorId, String grade, String amountPerLoan) {
        actAs(investorId, "ROLE_INVESTOR");
        autoInvestService.saveMyConfig(new UpdateAutoInvestRequest(
                true, List.of(grade), new BigDecimal("12"), 24, new BigDecimal(amountPerLoan)));
    }

    private MarketListingResponse openListing(String target, String grade) {
        actAs("ADMIN-AUTO", "ROLE_ADMIN");
        long loanId = LOAN_SEQUENCE.incrementAndGet();
        MarketListingResponse listing = listingService.createListingFrom(CreateListingRequest.builder()
                .loanId(loanId)
                .applicationNumber("LA-AUTO-" + loanId)
                .listingVersion(1)
                .fundingRound(1)
                .termsVersion("LOAN_TERMS_V1")
                .termsHash("a".repeat(64))
                .productCode("SP-01")
                .purpose("Vốn kinh doanh")
                .region("TP.HCM")
                .creditGrade(grade)
                .creditScore(720)
                .targetAmount(new BigDecimal(target))
                .annualInterestRate(new BigDecimal("15.0000"))
                .termMonths(12)
                .repaymentMethod("ANNUITY")
                .build());
        SecurityContextHolder.clearContext();
        return listing;
    }

    @Test
    @DisplayName("Người bật trước lấy phần của mình, người sau lấy phần còn lại; chạy lại không khớp trùng")
    void queueOrderAndIdempotentRerun() {
        enableAutoInvest("AUTO-FIRST", "B", "6000000");
        enableAutoInvest("AUTO-SECOND", "B", "6000000");
        MarketListingResponse listing = openListing("10000000.00", "B");
        SecurityContextHolder.clearContext();

        assertThat(matcher.processListing(listing.listingId())).isTrue();
        assertThat(matcher.processListing(listing.listingId())).isTrue();

        var saved = listingRepository.findById(listing.listingId()).orElseThrow();
        assertThat(saved.getCommittedAmount()).isEqualByComparingTo("10000000");
        assertThat(saved.getStatus()).isEqualTo(ListingStatus.FULLY_FUNDED);
        assertThat(saved.getAutoInvestProcessedAt()).isNotNull();

        List<AutoInvestMatch> matches = matchRepository.findByListingIdOrderByIdAsc(listing.listingId());
        assertThat(matches).extracting(AutoInvestMatch::getInvestorId).containsExactly("AUTO-FIRST", "AUTO-SECOND");
        assertThat(matches).extracting(AutoInvestMatch::getAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("6000000"), new BigDecimal("4000000"));
        assertThat(commitmentRepository.findAll().stream()
                .filter(commitment -> commitment.getListingId().equals(listing.listingId())))
                .hasSize(2);

        actAs("AUTO-SECOND", "ROLE_INVESTOR");
        List<AutoInvestMatchResponse> history = autoInvestService.myMatches(20);
        assertThat(history).first().satisfies(item -> {
            assertThat(item.outcome()).isEqualTo("MATCHED");
            assertThat(item.amount()).isEqualTo("4000000");
            assertThat(item.applicationNumber()).startsWith("LA-AUTO-");
        });
    }

    @Test
    @DisplayName("Khoản mở trước lúc bật và khoản khác hạng không được khớp")
    void noBackfillAndGradeFilter() {
        MarketListingResponse openedEarlier = openListing("10000000.00", "A");
        enableAutoInvest("AUTO-LATE", "A", "2000000");
        MarketListingResponse gradeC = openListing("10000000.00", "C");
        SecurityContextHolder.clearContext();

        matcher.processListing(openedEarlier.listingId());
        matcher.processListing(gradeC.listingId());

        assertThat(matchRepository.findByListingIdOrderByIdAsc(openedEarlier.listingId())).isEmpty();
        assertThat(matchRepository.findByListingIdOrderByIdAsc(gradeC.listingId())).isEmpty();
        assertThat(listingRepository.findById(gradeC.listingId()).orElseThrow().getCommittedAmount())
                .isEqualByComparingTo("0");
    }
}
