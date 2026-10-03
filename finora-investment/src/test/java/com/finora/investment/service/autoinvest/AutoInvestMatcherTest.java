package com.finora.investment.service.autoinvest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.domain.autoinvest.AutoInvestConfig;
import com.finora.investment.domain.autoinvest.AutoInvestMatch;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.order.InvestmentOrder;
import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.OrderResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.AutoInvestConfigRepository;
import com.finora.investment.repository.AutoInvestMatchRepository;
import com.finora.investment.repository.InvestmentOrderRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.service.FundingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Nhánh quyết định của {@link AutoInvestMatcher} với repository và FundingService giả.
 * Luồng trên PostgreSQL thật nằm ở {@code AutoInvestFlowIT}.
 */
class AutoInvestMatcherTest {

    private static final Instant ENABLED = Instant.parse("2026-09-30T08:00:00Z");
    private static final Instant OPENED = ENABLED.plusSeconds(600);
    private static final Instant NOW = OPENED.plusSeconds(5);
    private static final Long LISTING_ID = 7L;

    private final MarketListingRepository listingRepository = mock(MarketListingRepository.class);
    private final AutoInvestConfigRepository configRepository = mock(AutoInvestConfigRepository.class);
    private final AutoInvestMatchRepository matchRepository = mock(AutoInvestMatchRepository.class);
    private final InvestmentOrderRepository orderRepository = mock(InvestmentOrderRepository.class);
    private final FundingService fundingService = mock(FundingService.class);
    private final List<AutoInvestMatch> recorded = new ArrayList<>();

    private AutoInvestMatcher matcher;
    private MarketListing listing;

    @BeforeEach
    void setUp() {
        matcher = new AutoInvestMatcher(listingRepository, configRepository, matchRepository,
                orderRepository, fundingService, Clock.fixed(NOW, ZoneOffset.UTC));
        listing = MarketListing.builder()
                .id(LISTING_ID)
                .applicationNumber("LA-7")
                .creditGrade("A")
                .annualInterestRate(new BigDecimal("16.0000"))
                .termMonths(12)
                .targetAmount(new BigDecimal("10000000.00"))
                .committedAmount(BigDecimal.ZERO.setScale(2))
                .noteDenomination(new BigDecimal("1000000.00"))
                .minInvestmentAmount(new BigDecimal("1000000.00"))
                .status(ListingStatus.OPEN)
                .fundingOpenedAt(OPENED)
                .fundingClosesAt(NOW.plusSeconds(86_400))
                .build();
        when(listingRepository.findById(LISTING_ID)).thenAnswer(invocation -> Optional.of(listing));
        when(orderRepository.findByInvestorIdAndIdempotencyKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(matchRepository.save(any(AutoInvestMatch.class))).thenAnswer(invocation -> {
            recorded.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
    }

    private AutoInvestConfig config(String investorId, String amountPerLoan) {
        AutoInvestConfig config = AutoInvestConfig.create(investorId, ENABLED);
        config.update(true, List.of("A", "B"), new BigDecimal("15"), 18, new BigDecimal(amountPerLoan), ENABLED);
        return config;
    }

    private void candidates(AutoInvestConfig... configs) {
        when(configRepository.findCandidates(eq(LISTING_ID), any(), any(), any())).thenReturn(List.of(configs));
    }

    private OrderResponse committed(String reference, String amount) {
        return new OrderResponse(reference, LISTING_ID, amount, "COMMITTED", "HOLD-" + reference, null, null, NOW);
    }

    private OrderResponse rejected(String reference, String code) {
        return new OrderResponse(reference, LISTING_ID, "0", "REJECTED", null, code, "x", NOW);
    }

    @Test
    @DisplayName("Người bật trước được khớp trước; vốn còn lại cập nhật sau mỗi lệnh")
    void matchesInQueueOrder() {
        candidates(config("INV-A", "6000000"), config("INV-B", "6000000"));
        when(fundingService.placeOrderFor(eq("INV-A"), eq(LISTING_ID), eq("AUTO-7"), any()))
                .thenAnswer(invocation -> {
                    listing.setCommittedAmount(new BigDecimal("6000000.00"));
                    return committed("IO-A", "6000000.00");
                });
        when(fundingService.placeOrderFor(eq("INV-B"), eq(LISTING_ID), eq("AUTO-7"), any()))
                .thenReturn(committed("IO-B", "4000000.00"));

        assertThat(matcher.processListing(LISTING_ID)).isTrue();

        ArgumentCaptor<PlaceOrderRequest> request = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(fundingService).placeOrderFor(eq("INV-B"), eq(LISTING_ID), eq("AUTO-7"), request.capture());
        // Người sau chỉ còn 4 triệu vì người trước đã lấy 6 triệu.
        assertThat(request.getValue().amount()).isEqualByComparingTo("4000000");
        assertThat(recorded).extracting(AutoInvestMatch::getInvestorId).containsExactly("INV-A", "INV-B");
        assertThat(recorded).extracting(AutoInvestMatch::getOutcome)
                .containsOnly(AutoInvestMatch.Outcome.MATCHED);
        verify(listingRepository).markAutoInvestProcessed(eq(LISTING_ID), eq(NOW));
    }

    @Test
    @DisplayName("Ví không đủ tiền: ghi SKIPPED rồi xét người tiếp theo")
    void insufficientFundsSkipsToNextInvestor() {
        candidates(config("INV-A", "2000000"), config("INV-B", "2000000"));
        when(fundingService.placeOrderFor(eq("INV-A"), anyLong(), anyString(), any()))
                .thenReturn(rejected("IO-A", "PAYMENT_INSUFFICIENT_BALANCE"));
        when(fundingService.placeOrderFor(eq("INV-B"), anyLong(), anyString(), any()))
                .thenReturn(committed("IO-B", "2000000.00"));

        assertThat(matcher.processListing(LISTING_ID)).isTrue();

        assertThat(recorded).hasSize(2);
        assertThat(recorded.get(0).getOutcome()).isEqualTo(AutoInvestMatch.Outcome.SKIPPED);
        assertThat(recorded.get(0).getReason()).isEqualTo(AutoInvestMatcher.INSUFFICIENT_FUNDS);
        assertThat(recorded.get(1).getOutcome()).isEqualTo(AutoInvestMatch.Outcome.MATCHED);
    }

    @Test
    @DisplayName("Payment không phản hồi: dừng, không ghi nhật ký, không đánh dấu listing")
    void paymentUnavailableRetriesLater() {
        candidates(config("INV-A", "2000000"), config("INV-B", "2000000"));
        when(fundingService.placeOrderFor(eq("INV-A"), anyLong(), anyString(), any()))
                .thenThrow(InvestmentDomainException.conflict("PAYMENT_UNAVAILABLE", "bận"));

        assertThat(matcher.processListing(LISTING_ID)).isFalse();

        assertThat(recorded).isEmpty();
        verify(fundingService, never()).placeOrderFor(eq("INV-B"), anyLong(), anyString(), any());
        verify(listingRepository, never()).markAutoInvestProcessed(anyLong(), any());
    }

    @Test
    @DisplayName("Lần quét sau làm tiếp lệnh tự động còn dở với đúng số tiền cũ")
    void resumesPendingAutoOrderWithSameAmount() {
        candidates(config("INV-A", "5000000"));
        InvestmentOrder pending = InvestmentOrder.builder()
                .investorId("INV-A").listingId(LISTING_ID).amount(new BigDecimal("3000000.00")).build();
        when(orderRepository.findByInvestorIdAndIdempotencyKey("INV-A", "AUTO-7")).thenReturn(Optional.of(pending));
        when(fundingService.placeOrderFor(eq("INV-A"), anyLong(), anyString(), any()))
                .thenReturn(committed("IO-A", "3000000.00"));

        matcher.processListing(LISTING_ID);

        ArgumentCaptor<PlaceOrderRequest> request = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(fundingService).placeOrderFor(eq("INV-A"), eq(LISTING_ID), eq("AUTO-7"), request.capture());
        assertThat(request.getValue().amount()).isEqualByComparingTo("3000000");
        verify(orderRepository, never()).existsByListingIdAndInvestorIdAndStatusIn(anyLong(), anyString(), anyCollection());
    }

    @Test
    @DisplayName("Đã tự đặt tay vào khoản này thì Auto-Invest không đặt chồng")
    void skipsInvestorWithManualOrder() {
        candidates(config("INV-A", "2000000"));
        when(orderRepository.existsByListingIdAndInvestorIdAndStatusIn(eq(LISTING_ID), eq("INV-A"), anyCollection()))
                .thenReturn(true);

        matcher.processListing(LISTING_ID);

        verify(fundingService, never()).placeOrderFor(anyString(), anyLong(), anyString(), any());
        assertThat(recorded).singleElement()
                .extracting(AutoInvestMatch::getReason).isEqualTo(AutoInvestMatcher.ALREADY_INVESTED);
    }

    @Test
    @DisplayName("Vốn còn lại dưới mức tối thiểu: dừng cả listing")
    void stopsWhenRemainingBelowMinimum() {
        listing.setCommittedAmount(new BigDecimal("9500000.00"));
        candidates(config("INV-A", "2000000"));

        assertThat(matcher.processListing(LISTING_ID)).isTrue();

        verify(fundingService, never()).placeOrderFor(anyString(), anyLong(), anyString(), any());
        verify(listingRepository).markAutoInvestProcessed(eq(LISTING_ID), eq(NOW));
    }

    @Test
    @DisplayName("Hạng không nằm trong tiêu chí thì bỏ qua, không ghi nhật ký")
    void gradeOutsideCriteriaIsIgnored() {
        listing.setCreditGrade("C");
        candidates(config("INV-A", "2000000"));

        matcher.processListing(LISTING_ID);

        verify(fundingService, never()).placeOrderFor(anyString(), anyLong(), anyString(), any());
        assertThat(recorded).isEmpty();
    }
}
