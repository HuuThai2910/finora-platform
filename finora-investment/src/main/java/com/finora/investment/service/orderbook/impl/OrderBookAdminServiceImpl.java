package com.finora.investment.service.orderbook.impl;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.note.entity.InvestmentNote;
import com.finora.investment.domain.orderbook.entity.BookTrade;
import com.finora.investment.domain.orderbook.enums.OrderSide;
import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import com.finora.investment.domain.secondary.entity.NoteTransfer;
import com.finora.investment.dto.response.AdminNoteOwnershipResponse;
import com.finora.investment.dto.response.AdminTradeResponse;
import com.finora.investment.dto.response.OrderBookAdminSummaryResponse;
import com.finora.investment.repository.BookOrderRepository;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.NoteTransferRepository;
import com.finora.investment.repository.RestingTotalsView;
import com.finora.investment.repository.SettlementTotalsView;
import com.finora.investment.service.orderbook.OrderBookAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Đọc chợ Notes cho màn quản trị: các lần khớp kèm hai bên mua bán và trạng thái thanh toán, cùng
 * dải số liệu tổng. Quản trị không đặt hay huỷ lệnh thay nhà đầu tư; thao tác ghi duy nhất là gửi thanh toán lại
 * lần khớp "Cần đối soát".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBookAdminServiceImpl implements OrderBookAdminService {

    private final BookTradeRepository bookTradeRepository;
    private final BookOrderRepository bookOrderRepository;
    private final MarketListingRepository marketListingRepository;
    private final NoteTransferRepository noteTransferRepository;
    private final InvestmentNoteRepository investmentNoteRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Page<AdminTradeResponse> trades(SettlementStatus status, Pageable pageable) {
        Page<BookTrade> page = status == null
                ? bookTradeRepository.findAllByOrderByExecutedAtDescIdDesc(pageable)
                : bookTradeRepository.findBySettlementStatusOrderByExecutedAtDescIdDesc(status, pageable);
        // Mã khoản vay của cả trang trong một truy vấn.
        Map<Long, Long> loanIds = marketListingRepository
                .findAllById(page.getContent().stream().map(BookTrade::getListingId).distinct().toList())
                .stream().collect(Collectors.toMap(MarketListing::getId, MarketListing::getLoanId));
        return page.map(trade -> toResponse(trade, loanIds.get(trade.getListingId())));
    }

    @Override
    @Transactional
    public AdminTradeResponse requeueSettlement(String tradeReference) {
        BookTrade trade = bookTradeRepository.findByTradeReferenceForUpdate(tradeReference)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TRADE_NOT_FOUND",
                        "Không tìm thấy lần khớp"));
        if (trade.getSettlementStatus() != SettlementStatus.FAILED) {
            throw new BusinessException(HttpStatus.CONFLICT, "TRADE_NOT_FAILED",
                    "Lần khớp không còn ở trạng thái cần đối soát");
        }
        String actorId = SecurityUtils.getCurrentUserId();
        trade.requeueSettlement(actorId, clock.instant());
        log.warn("Nhân viên gửi thanh toán lại lần khớp: tradeReference={}, actor={}, requeueCount={}",
                trade.getTradeReference(), actorId, trade.getSettlementRequeueCount());
        Long loanId = marketListingRepository.findById(trade.getListingId()).map(MarketListing::getLoanId).orElse(null);
        return toResponse(trade, loanId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminNoteOwnershipResponse> tradeNotes(String tradeReference) {
        BookTrade trade = bookTradeRepository.findByTradeReference(tradeReference)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TRADE_NOT_FOUND",
                        "Không tìm thấy lần khớp"));
        List<Long> noteIds = noteTransferRepository.findByTradeIdOrderByNoteIdAsc(trade.getId()).stream()
                .map(NoteTransfer::getNoteId).distinct().toList();
        if (noteIds.isEmpty()) {
            return List.of();
        }
        List<AdminNoteOwnershipResponse> result = new ArrayList<>();
        for (InvestmentNote note : investmentNoteRepository.findAllById(noteIds)) {
            List<NoteTransfer> history = noteTransferRepository.findByNoteIdOrderByTransferredAtDesc(note.getId());
            // Mã lần khớp của từng dòng lịch sử: đọc cả nhóm trong một truy vấn.
            Map<Long, String> tradeReferences = bookTradeRepository
                    .findAllById(history.stream().map(NoteTransfer::getTradeId).distinct().toList()).stream()
                    .collect(Collectors.toMap(BookTrade::getId, BookTrade::getTradeReference));
            result.add(new AdminNoteOwnershipResponse(
                    note.getId(), note.getNoteNumber(), note.getListingId(), note.getStatus().name(),
                    note.getInvestorId(), note.getPrincipalAmount().toPlainString(),
                    note.getOutstandingPrincipal().toPlainString(), note.getIssuedAt(),
                    history.stream().map(transfer -> new AdminNoteOwnershipResponse.Transfer(
                            tradeReferences.get(transfer.getTradeId()), transfer.getSellerId(), transfer.getBuyerId(),
                            transfer.getPrice().toPlainString(), transfer.getOutstandingAtTransfer().toPlainString(),
                            transfer.getTransferredAt())).toList()));
        }
        result.sort(java.util.Comparator.comparing(AdminNoteOwnershipResponse::noteNumber));
        return result;
    }

    private AdminTradeResponse toResponse(BookTrade trade, Long loanId) {
        return new AdminTradeResponse(
                trade.getTradeReference(),
                trade.getListingId(),
                loanId,
                trade.getBuyerId(),
                trade.getSellerId(),
                trade.getAggressorSide().name(),
                OrderBookPricing.toPercent(trade.getPricePermille()),
                trade.getQuantity(),
                trade.getAmount().toPlainString(),
                trade.getPlatformFee().toPlainString(),
                trade.getSellerProceeds().toPlainString(),
                trade.getDefaulted(),
                trade.getSettlementStatus().name(),
                trade.getSettlementAttempts(),
                trade.getLastSettlementError(),
                trade.getExecutedAt(),
                trade.getSettledAt(),
                trade.getSettlementRequeueCount() == null ? 0 : trade.getSettlementRequeueCount(),
                trade.getSettlementRequeuedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderBookAdminSummaryResponse summary() {
        Map<SettlementStatus, SettlementTotalsView> settlement = bookTradeRepository.sumBySettlementStatus().stream()
                .collect(Collectors.toMap(SettlementTotalsView::status, Function.identity()));
        Map<OrderSide, RestingTotalsView> resting = bookOrderRepository.sumRestingBySide().stream()
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
