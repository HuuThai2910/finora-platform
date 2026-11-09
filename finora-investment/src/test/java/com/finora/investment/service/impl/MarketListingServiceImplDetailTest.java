package com.finora.investment.service.impl;

import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.integration.user.client.UserIdentityClient;
import com.finora.investment.mapper.InvestmentMapper;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.service.FundingSettingsService;
import com.finora.investment.service.ListingCommitmentCanceller;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Màn khoản vay hiện họ tên người vay đọc từ User; User lỗi thì vẫn trả khoản vay, chỉ thiếu họ tên. */
class MarketListingServiceImplDetailTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");

    private final MarketListingRepository marketListingRepository = mock(MarketListingRepository.class);
    private final UserIdentityClient userIdentityClient = mock(UserIdentityClient.class);
    private MarketListingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MarketListingServiceImpl(marketListingRepository, mock(InvestmentCommitmentRepository.class),
                new InvestmentMapper(), mock(FundingSettingsService.class), mock(InvestmentOutboxService.class),
                mock(ListingCommitmentCanceller.class), userIdentityClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void appSearchKeepsTheOldQueryAndAdvancedFilterUsesOneSpecificationQuery() {
        when(marketListingRepository.search(any(), any(), any(), any(), any(), any(), any(),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());
        when(marketListingRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        service.search(null, "A", new BigDecimal("12"), 12, "42", null, null, 0, 20);
        verify(marketListingRepository).search(any(), any(), any(), any(), any(), any(), any(),
                any(org.springframework.data.domain.Pageable.class));

        service.search("ALL", null, null, null, null, null, null, new com.finora.investment.dto.request.ListingAdvancedFilter(
                new BigDecimal("1000000"), null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null), 0, 20);
        verify(marketListingRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void detailCarriesBorrowerFullName() {
        when(marketListingRepository.findById(5L)).thenReturn(Optional.of(listing("42")));
        when(userIdentityClient.identity("42"))
                .thenReturn(new UserIdentityClient.UserIdentity("42", "Nguyễn Văn An", "********1234"));

        MarketListingResponse response = service.detail(5L);

        assertThat(response.borrowerFullName()).isEqualTo("Nguyễn Văn An");
    }

    @Test
    void detailStillLoadsWhenUserIsUnavailable() {
        when(marketListingRepository.findById(5L)).thenReturn(Optional.of(listing("42")));
        when(userIdentityClient.identity("42")).thenThrow(new UserIdentityClient.UserIdentityUnavailableException(
                "USER_IDENTITY_UNAVAILABLE", "Không kết nối được User Service", null));

        MarketListingResponse response = service.detail(5L);

        assertThat(response.listingId()).isEqualTo(5L);
        assertThat(response.borrowerFullName()).isNull();
    }

    @Test
    void detailCarriesDisplayCodesNextToLookupNumbers() {
        MarketListing listing = listing(null);
        listing.setApplicationCode("HS-20261109-3RXA");
        listing.setContractNumber("LC-ABCDEF0123456789ABCD");
        listing.setContractCode("HĐ-20261110-K7QM");
        when(marketListingRepository.findById(5L)).thenReturn(Optional.of(listing));

        MarketListingResponse response = service.detail(5L);

        assertThat(response.applicationNumber()).isEqualTo("LA-0123456789ABCDEF0123");
        assertThat(response.applicationCode()).isEqualTo("HS-20261109-3RXA");
        assertThat(response.contractNumber()).isEqualTo("LC-ABCDEF0123456789ABCD");
        assertThat(response.contractCode()).isEqualTo("HĐ-20261110-K7QM");
    }

    @Test
    void detailSkipsUserWhenListingHasNoBorrower() {
        when(marketListingRepository.findById(5L)).thenReturn(Optional.of(listing(null)));

        assertThat(service.detail(5L).borrowerFullName()).isNull();
        verifyNoInteractions(userIdentityClient);
    }

    private static MarketListing listing(String borrowerId) {
        return MarketListing.builder()
                .id(5L).loanId(77L).applicationNumber("LA-0123456789ABCDEF0123").borrowerId(borrowerId)
                .productCode("SP-01").purpose("Chi phí giáo dục").region("Không công bố")
                .creditGrade("B").creditScore(80)
                .targetAmount(new BigDecimal("10000000.00")).committedAmount(new BigDecimal("4000000.00"))
                .annualInterestRate(new BigDecimal("15.0000")).termMonths(12).repaymentMethod("ANNUITY")
                .noteDenomination(new BigDecimal("1000000.00")).minInvestmentAmount(new BigDecimal("1000000.00"))
                .status(ListingStatus.OPEN).fundingRound(1)
                .fundingOpenedAt(NOW).fundingClosesAt(NOW.plusSeconds(86_400))
                .build();
    }
}
