package com.finora.investment.service.orderbook.impl;
import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.note.entity.InvestmentNote;
import com.finora.investment.domain.orderbook.entity.BookTrade;
import com.finora.investment.domain.orderbook.enums.OrderSide;
import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import com.finora.investment.domain.orderbook.enums.TradeProgress;
import com.finora.investment.domain.secondary.entity.NoteTransfer;
import com.finora.investment.dto.request.AdminTradeFilter;
import com.finora.investment.dto.response.AdminNoteOwnershipResponse;
import com.finora.investment.dto.response.AdminTradeResponse;
import com.finora.investment.dto.response.OrderBookAdminSummaryResponse;
import com.finora.investment.repository.BookOrderRepository;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.BookTradeSpecifications;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.NoteAskPriceView;
import com.finora.investment.repository.NoteLockRepository;
import com.finora.investment.repository.NoteTransferDocumentRepository;
import com.finora.investment.repository.NoteTransferRepository;
import com.finora.investment.repository.RestingTotalsView;
import com.finora.investment.repository.SettlementTotalsView;
import com.finora.investment.repository.TradeDocumentView;
import com.finora.investment.service.BorrowerNameLookup;
import com.finora.investment.service.PartyNameSearch;
import com.finora.investment.service.orderbook.OrderBookAdminService;
import com.finora.investment.util.SearchPatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Đọc chợ Notes cho màn quản trị: các lần khớp kèm hai bên mua bán, trạng thái thanh toán và giấy xác nhận, cùng
 * dải số liệu tổng. Quản trị không đặt hay huỷ lệnh thay nhà đầu tư; thao tác ghi duy nhất là gửi thanh toán lại
 * lần khớp "Cần đối soát".
 *
 * <p>Họ tên hai bên đọc từ User theo lô cho cả trang ({@link BorrowerNameLookup}, có đệm); hàm đọc không bọc
 * transaction để lời gọi User không giữ kết nối cơ sở dữ liệu.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBookAdminServiceImpl implements OrderBookAdminService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("executedAt"), Sort.Order.desc("id"));

    private final BookTradeRepository bookTradeRepository;
    private final BookOrderRepository bookOrderRepository;
    private final MarketListingRepository marketListingRepository;
    private final NoteTransferRepository noteTransferRepository;
    private final InvestmentNoteRepository investmentNoteRepository;
    private final NoteLockRepository noteLockRepository;
    private final NoteTransferDocumentRepository noteTransferDocumentRepository;
    private final BorrowerNameLookup nameLookup;
    private final PartyNameSearch partyNameSearch;
    private final Clock clock;

    @Override
    public Page<AdminTradeResponse> searchTrades(AdminTradeFilter filter, Pageable pageable) {
        AdminTradeFilter applied = (filter == null ? AdminTradeFilter.NONE : filter).validate();
        Set<String> partyIds = null;
        if (PartyNameSearch.searchable(applied.party())) {
            partyIds = partyNameSearch.idsMatching(applied.party());
            if (partyIds.isEmpty()) {
                return Page.empty(pageable);
            }
        }
        Page<BookTrade> page = bookTradeRepository.findAll(
                BookTradeSpecifications.matching(applied, SearchPatterns.containsCode(applied.q()), partyIds,
                        BUSINESS_ZONE),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST));
        return new PageImpl<>(toResponses(page.getContent()), page.getPageable(), page.getTotalElements());
    }

    @Override
    public AdminTradeResponse trade(String tradeReference) {
        return toResponses(List.of(findTrade(tradeReference))).get(0);
    }

    @Override
    @Transactional
    public AdminTradeResponse requeueSettlement(String tradeReference) {
        BookTrade trade = bookTradeRepository.findByTradeReferenceForUpdate(tradeReference)
                .orElseThrow(OrderBookAdminServiceImpl::tradeNotFound);
        if (trade.getSettlementStatus() != SettlementStatus.FAILED) {
            throw new BusinessException(HttpStatus.CONFLICT, "TRADE_NOT_FAILED",
                    "Lần khớp không còn ở trạng thái cần đối soát");
        }
        String actorId = SecurityUtils.getCurrentUserId();
        trade.requeueSettlement(actorId, clock.instant());
        log.warn("Nhân viên gửi thanh toán lại lần khớp: tradeReference={}, actor={}, requeueCount={}",
                trade.getTradeReference(), actorId, trade.getSettlementRequeueCount());
        return toResponses(List.of(trade)).get(0);
    }

    @Override
    public List<AdminNoteOwnershipResponse> tradeNotes(String tradeReference) {
        BookTrade trade = findTrade(tradeReference);
        List<Long> noteIds = noteTransferRepository.findByTradeIdOrderByNoteIdAsc(trade.getId()).stream()
                .map(NoteTransfer::getNoteId).distinct().toList();
        if (noteIds.isEmpty()) {
            return List.of();
        }
        List<InvestmentNote> notes = investmentNoteRepository.findAllById(noteIds);
        Map<Long, List<NoteTransfer>> histories = notes.stream().collect(Collectors.toMap(InvestmentNote::getId,
                note -> noteTransferRepository.findByNoteIdOrderByTransferredAtDesc(note.getId())));
        // Mã lần khớp của mọi dòng lịch sử, giá đang rao bán và họ tên: mỗi loại một truy vấn cho cả nhóm.
        Map<Long, String> tradeReferences = bookTradeRepository
                .findAllById(histories.values().stream().flatMap(List::stream).map(NoteTransfer::getTradeId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(BookTrade::getId, BookTrade::getTradeReference));
        Map<Long, Integer> askPrices = noteLockRepository.findAskPrices(noteIds).stream()
                .collect(Collectors.toMap(NoteAskPriceView::noteId, NoteAskPriceView::pricePermille, (a, b) -> a));
        Map<String, String> names = nameLookup.fullNamesOf(Stream.concat(
                notes.stream().map(InvestmentNote::getInvestorId),
                histories.values().stream().flatMap(List::stream)
                        .flatMap(transfer -> Stream.of(transfer.getSellerId(), transfer.getBuyerId())))
                .collect(Collectors.toSet()));

        List<AdminNoteOwnershipResponse> result = new ArrayList<>();
        for (InvestmentNote note : notes) {
            Integer ask = askPrices.get(note.getId());
            result.add(new AdminNoteOwnershipResponse(
                    note.getId(), note.getNoteNumber(), note.getListingId(), note.getStatus().name(),
                    note.getInvestorId(), note.getPrincipalAmount().toPlainString(),
                    note.getOutstandingPrincipal().toPlainString(), note.getIssuedAt(),
                    histories.get(note.getId()).stream().map(transfer -> new AdminNoteOwnershipResponse.Transfer(
                            tradeReferences.get(transfer.getTradeId()), transfer.getSellerId(), transfer.getBuyerId(),
                            transfer.getPrice().toPlainString(), transfer.getOutstandingAtTransfer().toPlainString(),
                            transfer.getTransferredAt(), names.get(transfer.getSellerId()),
                            names.get(transfer.getBuyerId()))).toList(),
                    names.get(note.getInvestorId()),
                    ask == null ? null : OrderBookPricing.toPercent(ask)));
        }
        result.sort(Comparator.comparing(AdminNoteOwnershipResponse::noteNumber));
        return result;
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

    private BookTrade findTrade(String tradeReference) {
        return bookTradeRepository.findByTradeReference(tradeReference)
                .orElseThrow(OrderBookAdminServiceImpl::tradeNotFound);
    }

    private static BusinessException tradeNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "TRADE_NOT_FOUND", "Không tìm thấy lần khớp");
    }

    /** Dựng response cho một nhóm lần khớp: khoản vay, giấy xác nhận và họ tên hai bên, mỗi loại một lần đọc. */
    private List<AdminTradeResponse> toResponses(List<BookTrade> trades) {
        if (trades.isEmpty()) {
            return List.of();
        }
        Map<Long, MarketListing> listings = marketListingRepository
                .findAllById(trades.stream().map(BookTrade::getListingId).distinct().toList()).stream()
                .collect(Collectors.toMap(MarketListing::getId, Function.identity()));
        Map<String, TradeDocumentView> documents = noteTransferDocumentRepository
                .findViewsByTradeReferences(trades.stream().map(BookTrade::getTradeReference).toList()).stream()
                .collect(Collectors.toMap(TradeDocumentView::tradeReference, Function.identity(), (a, b) -> a));
        Set<String> partyIds = new HashSet<>();
        trades.forEach(trade -> {
            partyIds.add(trade.getBuyerId());
            partyIds.add(trade.getSellerId());
        });
        Map<String, String> names = nameLookup.fullNamesOf(partyIds);
        return trades.stream()
                .map(trade -> toResponse(trade, listings.get(trade.getListingId()),
                        documents.get(trade.getTradeReference()), names))
                .toList();
    }

    private static AdminTradeResponse toResponse(BookTrade trade, MarketListing listing, TradeDocumentView document,
                                                 Map<String, String> names) {
        return new AdminTradeResponse(
                trade.getTradeReference(),
                trade.getListingId(),
                listing == null ? null : listing.getLoanId(),
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
                trade.getSettlementRequeuedAt(),
                listing == null ? null : listing.getApplicationCode(),
                listing == null ? null : listing.getPurpose(),
                listing == null ? null : listing.getPurposeCode(),
                names.get(trade.getBuyerId()),
                names.get(trade.getSellerId()),
                outstandingPerNote(trade),
                document == null ? null : document.status().name(),
                document == null ? null : document.documentNumber(),
                document == null ? null : document.issuedAt(),
                TradeProgress.of(trade.getSettlementStatus(), document == null ? null : document.status()).name());
    }

    /** Dư nợ gốc mỗi phần vốn lúc khớp: tổng dư nợ của lần khớp chia số phần vốn, tới đồng lẻ. */
    private static String outstandingPerNote(BookTrade trade) {
        if (trade.getOutstandingTotal() == null || trade.getQuantity() == null || trade.getQuantity() <= 0) {
            return null;
        }
        return trade.getOutstandingTotal()
                .divide(BigDecimal.valueOf(trade.getQuantity()), 2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO.setScale(2) : value).toPlainString();
    }
}
