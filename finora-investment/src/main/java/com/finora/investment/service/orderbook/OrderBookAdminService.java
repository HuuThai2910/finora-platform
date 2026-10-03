package com.finora.investment.service.orderbook;

import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.orderbook.BookTrade;
import com.finora.investment.domain.orderbook.OrderSide;
import com.finora.investment.domain.orderbook.SettlementStatus;
import com.finora.investment.dto.response.AdminTradeResponse;
import com.finora.investment.dto.response.OrderBookAdminSummaryResponse;
import com.finora.investment.repository.BookOrderRepository;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.RestingTotalsView;
import com.finora.investment.repository.SettlementTotalsView;
import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc chợ Notes cho màn quản trị: các lần khớp kèm hai bên mua bán và trạng thái thanh toán, cùng
 * dải số liệu tổng. Chỉ đọc — quản trị không đặt hay huỷ lệnh thay nhà đầu tư.
 */
@Service
@RequiredArgsConstructor
public class OrderBookAdminService {

    private final BookTradeRepository tradeRepository;
    private final BookOrderRepository orderRepository;
    private final MarketListingRepository listingRepository;

    @Transactional(readOnly = true)
    public Page<AdminTradeResponse> trades(SettlementStatus status, Pageable pageable) {
        Page<BookTrade> page = status == null
                ? tradeRepository.findAllByOrderByExecutedAtDescIdDesc(pageable)
                : tradeRepository.findBySettlementStatusOrderByExecutedAtDescIdDesc(status, pageable);
        // Mã khoản vay của cả trang trong một truy vấn.
        Map<Long, Long> loanIds = listingRepository
                .findAllById(page.getContent().stream().map(BookTrade::getListingId).distinct().toList())
                .stream().collect(Collectors.toMap(MarketListing::getId, MarketListing::getLoanId));
        return page.map(t -> new AdminTradeResponse(
                t.getTradeReference(),
                t.getListingId(),
                loanIds.get(t.getListingId()),
                t.getBuyerId(),
                t.getSellerId(),
                t.getAggressorSide().name(),
                OrderBookPricing.toPercent(t.getPricePermille()),
                t.getQuantity(),
                t.getAmount().toPlainString(),
                t.getPlatformFee().toPlainString(),
                t.getSellerProceeds().toPlainString(),
                t.getDefaulted(),
                t.getSettlementStatus().name(),
                t.getSettlementAttempts(),
                t.getLastSettlementError(),
                t.getExecutedAt(),
                t.getSettledAt()));
    }

    @Transactional(readOnly = true)
    public OrderBookAdminSummaryResponse summary() {
        Map<SettlementStatus, SettlementTotalsView> settlement = tradeRepository.sumBySettlementStatus().stream()
                .collect(Collectors.toMap(SettlementTotalsView::status, Function.identity()));
        Map<OrderSide, RestingTotalsView> resting = orderRepository.sumRestingBySide().stream()
                .collect(Collectors.toMap(RestingTotalsView::side, Function.identity()));

        SettlementTotalsView settled = settlement.get(SettlementStatus.SETTLED);
        SettlementTotalsView pending = settlement.get(SettlementStatus.PENDING);
        SettlementTotalsView failed = settlement.get(SettlementStatus.FAILED);
        RestingTotalsView bids = resting.get(OrderSide.BID);
        RestingTotalsView asks = resting.get(OrderSide.ASK);

        return new OrderBookAdminSummaryResponse(
                settled == null ? 0 : settled.count(),
                money(settled == null ? null : settled.amount()),
                money(settled == null ? null : settled.platformFee()),
                pending == null ? 0 : pending.count(),
                money(pending == null ? null : pending.amount()),
                failed == null ? 0 : failed.count(),
                bids == null ? 0 : bids.orders(),
                bids == null ? 0 : bids.notes(),
                asks == null ? 0 : asks.orders(),
                asks == null ? 0 : asks.notes());
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO.setScale(2) : value).toPlainString();
    }
}
