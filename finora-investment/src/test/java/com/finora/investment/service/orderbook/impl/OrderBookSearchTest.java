package com.finora.investment.service.orderbook.impl;

import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ô tìm của sổ lệnh (app) và bảng lần khớp quản trị. */
@ExtendWith(MockitoExtension.class)
class OrderBookSearchTest {

    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private BookTradeRepository bookTradeRepository;
    @Mock
    private InvestmentNoteRepository investmentNoteRepository;
    @Mock
    private MarketListingRepository marketListingRepository;
    @InjectMocks
    private OrderBookAdminServiceImpl adminService;
    @InjectMocks
    private OrderBookQueryServiceImpl queryService;

    @Test
    void tradeSearchGoesThroughOneSpecificationQueryNewestFirst() {
        when(bookTradeRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        adminService.trades(null, "hs-20261109", PageRequest.of(2, 20));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(bookTradeRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getSort().toList()).extracting(Sort.Order::getProperty)
                .containsExactly("executedAt", "id");
    }

    @Test
    void bookSearchFiltersTradableListingsByCode() {
        when(investmentNoteRepository.searchTradableListingIds(isNull(), eq("%HĐ-2026%"), any()))
                .thenReturn(Page.empty());

        queryService.listBooks(null, "hd-2026", PageRequest.of(0, 20));

        verify(investmentNoteRepository).searchTradableListingIds(isNull(), eq("%HĐ-2026%"), any());
        verify(investmentNoteRepository, never()).findTradableListingIds(any(), any());
    }
}
